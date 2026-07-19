package com.salesmanager.crm.leadimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.masterdata.MasterType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Bulk Lead import (preview + commit) - the two-step .xlsx/.csv upload flow that backfills an
 * org's historical clients directly into the Lead table without spamming stub Visits or
 * forcing every row to status=NEW (the key behavioral differences from LeadService#create -
 * see LeadImportService's class javadoc). Follows the same Testcontainers/helper-method style
 * as LeadCrudIT/LeadReassignmentIT, plus a hand-rolled multipart-upload helper (no existing
 * test in this codebase does a multipart upload yet).
 */
class LeadImportIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preview_suggestsMapping_andReturnsHeaderAndPreviewRows_forBothXlsxAndCsv() {
        AuthResponse admin = registerOrganization("Import Preview Org");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone", "Email", "City", "Industry");
        List<List<String>> rows = List.of(
                List.of("Acme Corp", "Jane Buyer", "9812345601", "jane@acme.test", "Mumbai", "IT Services"),
                List.of("Beta Traders", "Bob Seller", "9812345602", "bob@beta.test", "Pune", "Retail"));

        // CSV
        ResponseEntity<String> csvResponse = postMultipartPreview(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv");
        assertThat(csvResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertPreviewShape(parse(csvResponse.getBody()), headers, rows.size());

        // XLSX
        ResponseEntity<String> xlsxResponse = postMultipartPreview(admin.accessToken(),
                buildXlsx(headers, rows), "clients.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsxResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertPreviewShape(parse(xlsxResponse.getBody()), headers, rows.size());
    }

    private void assertPreviewShape(JsonNode body, List<String> headers, int expectedRowCount) {
        JsonNode headerNode = body.get("headers");
        assertThat(headerNode.size()).isEqualTo(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            assertThat(headerNode.get(i).asText()).isEqualTo(headers.get(i));
        }
        assertThat(body.get("totalDataRowCount").asInt()).isEqualTo(expectedRowCount);
        assertThat(body.get("previewRows").size()).isEqualTo(expectedRowCount);

        JsonNode mapping = body.get("suggestedMapping");
        assertThat(mapping.get("companyName").asInt()).isEqualTo(0);
        assertThat(mapping.get("contactPerson").asInt()).isEqualTo(1);
        assertThat(mapping.get("contactNo").asInt()).isEqualTo(2);
        assertThat(mapping.get("email").asInt()).isEqualTo(3);
        assertThat(mapping.get("city").asInt()).isEqualTo(4);
        assertThat(mapping.get("industry").asInt()).isEqualTo(5);
    }

    @Test
    void commit_createsLeads_resolvesKnownMasterLabel_andFallsBackToOtherForUnknownLabel() {
        AuthResponse admin = registerOrganization("Import Commit Org");
        int cityCountBefore = allMasters(admin.accessToken(), MasterType.CITY).size();
        int industryCountBefore = allMasters(admin.accessToken(), MasterType.INDUSTRY).size();

        List<String> headers = List.of("Company Name", "Contact Person", "Phone", "Email", "City", "Industry");
        List<List<String>> rows = List.of(
                List.of("Acme Corp", "Jane Buyer", "9812345611", "jane@acme.test", "Mumbai", "Not A Real Industry"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2,
                "email", 3, "city", 4, "industry", 5);

        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = parse(commitResponse.getBody());
        assertThat(result.get("totalRows").asInt()).isEqualTo(1);
        assertThat(result.get("importedCount").asInt()).isEqualTo(1);
        assertThat(result.get("skippedDuplicateCount").asInt()).isEqualTo(0);
        assertThat(result.get("errorCount").asInt()).isEqualTo(0);

        JsonNode leads = getLeadsList(admin.accessToken());
        assertThat(leads.get("content").size()).isEqualTo(1);
        JsonNode lead = leads.get("content").get(0);
        assertThat(lead.get("companyName").asText()).isEqualTo("Acme Corp");
        assertThat(lead.get("contactPerson").asText()).isEqualTo("Jane Buyer");
        assertThat(lead.get("contactNo").asText()).isEqualTo("9812345611");
        // "Mumbai" matches a seeded CITY label -> resolves to that row's id, no cityOther.
        assertThat(lead.get("cityId").isNull()).isFalse();
        assertThat(lead.get("cityOther").isNull()).isTrue();
        // "Not A Real Industry" matches no seeded INDUSTRY label -> free-text fallback only.
        assertThat(lead.get("industryId").isNull()).isTrue();
        assertThat(lead.get("industryOther").asText()).isEqualTo("Not A Real Industry");

        // Never auto-promoted into master data - row counts for both types are unchanged.
        assertThat(allMasters(admin.accessToken(), MasterType.CITY).size()).isEqualTo(cityCountBefore);
        assertThat(allMasters(admin.accessToken(), MasterType.INDUSTRY).size()).isEqualTo(industryCountBefore);
    }

    @Test
    void commit_rowMissingRequiredField_isSkippedAndReported_restOfBatchStillImports() {
        AuthResponse admin = registerOrganization("Import Missing Field Org");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone");
        List<List<String>> rows = List.of(
                List.of("Good Co", "Good Contact", ""), // blank contactNo - required
                List.of("Fine Co", "Fine Contact", "9812345621"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);

        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = parse(commitResponse.getBody());
        assertThat(result.get("totalRows").asInt()).isEqualTo(2);
        assertThat(result.get("importedCount").asInt()).isEqualTo(1);
        assertThat(result.get("errorCount").asInt()).isEqualTo(1);
        JsonNode error = result.get("errors").get(0);
        assertThat(error.get("rowNumber").asInt()).isEqualTo(2); // header=row1, first data row=row2
        assertThat(error.get("message").asText()).contains("contactNo");

        JsonNode leads = getLeadsList(admin.accessToken());
        assertThat(leads.get("content").size()).isEqualTo(1);
        assertThat(leads.get("content").get(0).get("companyName").asText()).isEqualTo("Fine Co");
    }

    @Test
    void commit_rowDuplicatingExistingLead_isSkippedAndReported_restOfBatchStillImports() {
        AuthResponse admin = registerOrganization("Import Duplicate Org");
        Masters masters = loadMasters(admin.accessToken());
        Map<String, Object> existingLeadBody = minimalLeadBody(masters, "Existing Co", "Existing Contact",
                "9812345631");
        ResponseEntity<String> existingCreated = post("/leads", admin.accessToken(), existingLeadBody);
        assertThat(existingCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String existingLeadId = parse(existingCreated.getBody()).get("id").asText();

        List<String> headers = List.of("Company Name", "Contact Person", "Phone");
        List<List<String>> rows = List.of(
                List.of("Existing Co Reimport", "Someone Else", "9812345631"), // same contactNo -> duplicate
                List.of("Truly New Co", "New Contact", "9812345632"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);

        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = parse(commitResponse.getBody());
        assertThat(result.get("totalRows").asInt()).isEqualTo(2);
        assertThat(result.get("importedCount").asInt()).isEqualTo(1);
        assertThat(result.get("skippedDuplicateCount").asInt()).isEqualTo(1);
        JsonNode skipped = result.get("skippedDuplicates").get(0);
        assertThat(skipped.get("rowNumber").asInt()).isEqualTo(2);
        assertThat(skipped.get("companyName").asText()).isEqualTo("Existing Co Reimport");
        assertThat(skipped.get("existingLeadId").asText()).isEqualTo(existingLeadId);

        // The genuinely-new row still imported despite the other row being a duplicate.
        JsonNode leads = getLeadsList(admin.accessToken());
        assertThat(leads.get("content").size()).isEqualTo(2); // the pre-existing lead + the new import
    }

    @Test
    void commit_importedLead_getsNoStubVisit_butExactlyOneLeadCreatedActivityEntry() {
        AuthResponse admin = registerOrganization("Import No Visit Org");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone");
        List<List<String>> rows = List.of(List.of("No Visit Co", "No Visit Contact", "9812345641"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);

        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode leads = getLeadsList(admin.accessToken());
        assertThat(leads.get("content").size()).isEqualTo(1);
        String leadId = leads.get("content").get(0).get("id").asText();

        // No auto-created stub Visit - the key behavioral difference from LeadService#create.
        JsonNode visits = parse(get("/visits?leadId=" + leadId, admin.accessToken()).getBody());
        assertThat(visits.get("content").size()).isEqualTo(0);

        // Exactly one LEAD_CREATED activity-log entry for the imported lead.
        JsonNode activity = parse(get("/activity?leadId=" + leadId, admin.accessToken()).getBody());
        assertThat(activity.get("content").size()).isEqualTo(1);
        assertThat(activity.get("content").get(0).get("type").asText()).isEqualTo("LEAD_CREATED");
    }

    @Test
    void commit_defaultStatusAppliesUnlessRowOverridesIt() {
        AuthResponse admin = registerOrganization("Import Status Org");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone", "Status");
        List<List<String>> rows = List.of(
                List.of("Default Status Co", "Contact One", "9812345651", ""), // no override -> defaultStatus
                List.of("Overridden Status Co", "Contact Two", "9812345652", "Negotiation"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2, "status", 3);

        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "CLOSED_WON"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(commitResponse.getBody()).get("importedCount").asInt()).isEqualTo(2);

        JsonNode leads = getLeadsList(admin.accessToken());
        Map<String, String> statusByCompany = new HashMap<>();
        for (JsonNode lead : leads.get("content")) {
            statusByCompany.put(lead.get("companyName").asText(), lead.get("status").asText());
        }
        assertThat(statusByCompany.get("Default Status Co")).isEqualTo("CLOSED_WON");
        assertThat(statusByCompany.get("Overridden Status Co")).isEqualTo("NEGOTIATION");
    }

    @Test
    void nonAdminEmployee_getsForbidden_onBothPreviewAndCommit() {
        AuthResponse admin = registerOrganization("Import Forbidden Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "importForbidden");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone");
        List<List<String>> rows = List.of(List.of("Co", "Contact", "9812345661"));

        ResponseEntity<String> previewResponse = postMultipartPreview(employee.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv");
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);
        ResponseEntity<String> commitResponse = postMultipartCommit(employee.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenantIsolation_orgAImport_isNotVisibleToOrgB() {
        AuthResponse orgA = registerOrganization("Import Isolation Org A");
        AuthResponse orgB = registerOrganization("Import Isolation Org B");
        List<String> headers = List.of("Company Name", "Contact Person", "Phone");
        List<List<String>> rows = List.of(List.of("Org A Only Co", "Org A Contact", "9812345671"));
        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);

        ResponseEntity<String> commitResponse = postMultipartCommit(orgA.accessToken(),
                buildCsv(headers, rows), "clients.csv", "text/csv",
                commitBody(mapping, orgA.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(commitResponse.getBody()).get("importedCount").asInt()).isEqualTo(1);

        assertThat(getLeadsList(orgA.accessToken()).get("content").size()).isEqualTo(1);
        assertThat(getLeadsList(orgB.accessToken()).get("content").size()).isEqualTo(0);
    }

    @Test
    void unsupportedFileType_isRejectedWithBadRequest_notServerError() {
        AuthResponse admin = registerOrganization("Import Unsupported Type Org");
        byte[] content = "just some plain text, not a spreadsheet".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> previewResponse = postMultipartPreview(admin.accessToken(),
                content, "clients.txt", "text/plain");
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Integer> mapping = mapping("companyName", 0, "contactPerson", 1, "contactNo", 2);
        ResponseEntity<String> commitResponse = postMultipartCommit(admin.accessToken(),
                content, "clients.pdf", "application/pdf",
                commitBody(mapping, admin.employeeId(), "NEW"));
        assertThat(commitResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- helpers ----

    private record Masters(String cityId, String leadSourceId, String industryId) {
    }

    private Masters loadMasters(String adminToken) {
        return new Masters(
                firstMasterId(adminToken, MasterType.CITY),
                firstMasterId(adminToken, MasterType.LEAD_SOURCE),
                firstMasterId(adminToken, MasterType.INDUSTRY));
    }

    private Map<String, Object> minimalLeadBody(Masters masters, String companyName, String contactPerson,
                                                 String contactNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", companyName);
        body.put("contactPerson", contactPerson);
        body.put("contactNo", contactNo);
        body.put("cityId", masters.cityId);
        body.put("leadSourceId", masters.leadSourceId);
        body.put("industryId", masters.industryId);
        return body;
    }

    private String firstMasterId(String token, MasterType type) {
        JsonNode entries = allMasters(token, type);
        assertThat(entries.size()).isGreaterThan(0);
        return entries.get(0).get("id").asText();
    }

    private JsonNode allMasters(String token, MasterType type) {
        ResponseEntity<String> response = get("/masters/" + type + "?includeInactive=true", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private JsonNode getLeadsList(String token) {
        ResponseEntity<String> response = get("/leads", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(response.getBody());
    }

    private Map<String, Integer> mapping(Object... keyIndexPairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < keyIndexPairs.length; i += 2) {
            result.put((String) keyIndexPairs[i], (Integer) keyIndexPairs[i + 1]);
        }
        return result;
    }

    private Map<String, Object> commitBody(Map<String, Integer> columnMapping, UUID defaultOwnerId,
                                            String defaultStatus) {
        Map<String, Object> body = new HashMap<>();
        body.put("columnMapping", columnMapping);
        body.put("defaultOwnerId", defaultOwnerId.toString());
        body.put("defaultStatus", defaultStatus);
        return body;
    }

    private byte[] buildCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append("\r\n");
        for (List<String> row : rows) {
            sb.append(String.join(",", row)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildXlsx(List<String> headers, List<List<String>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Leads");
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> postMultipartPreview(String token, byte[] fileBytes, String filename,
                                                          String contentType) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart(fileBytes, filename, contentType));
        return postMultipart("/leads/import/preview", token, body);
    }

    private ResponseEntity<String> postMultipartCommit(String token, byte[] fileBytes, String filename,
                                                         String contentType, Map<String, Object> requestBody) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart(fileBytes, filename, contentType));
        body.add("request", jsonPart(requestBody));
        return postMultipart("/leads/import/commit", token, body);
    }

    private ResponseEntity<String> postMultipart(String path, String token, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private HttpEntity<ByteArrayResource> filePart(byte[] content, String filename, String contentType) {
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        return new HttpEntity<>(resource, partHeaders);
    }

    private HttpEntity<String> jsonPart(Map<String, Object> requestBody) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        try {
            return new HttpEntity<>(objectMapper.writeValueAsString(requestBody), partHeaders);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@leadimport.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@leadimport.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee " + label,
                "email", email,
                "password", password,
                "role", Role.EMPLOYEE.name());
        ResponseEntity<String> createResponse = post("/employees", adminToken, body);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        LoginRequest loginRequest = new LoginRequest(email, password);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl() + "/auth/login", new HttpEntity<>(loginRequest, loginHeaders), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResponse.getBody();
    }
}
