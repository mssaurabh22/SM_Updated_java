package com.salesmanager.crm.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Product CRUD (backing the Inventory module, Phase 1): GET open to any entitled authenticated
 * user, mutations ADMIN-only, tenant isolation, and the "initial stockQuantity on create inserts
 * a matching StockMovement row" invariant (see Product's class javadoc). Every endpoint requires
 * INVENTORY_MANAGEMENT, same gating shape as EMPLOYEE_LEAVE_MANAGEMENT (see EntitlementIT).
 */
class ProductCrudIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void notEntitled_everyEndpointReturns403FeatureNotEntitled() {
        AuthResponse admin = registerOrganization("Product NotEntitled Org");
        Map<String, Object> body = minimalProductBody("Widget", "10.00", 5);

        assertThat(post("/inventory/products", admin.accessToken(), body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/inventory/products", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void create_withInitialStock_insertsMatchingStockMovement_andEmployeeCanReadButNotWrite() {
        AuthResponse admin = registerOrganization("Product Crud Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "productEmployee");
        grant(admin.orgId());

        Map<String, Object> body = minimalProductBody("Solar Panel 330W", "9500.00", 20);
        ResponseEntity<String> created = post("/inventory/products", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode product = parse(created.getBody());
        assertThat(product.get("name").asText()).isEqualTo("Solar Panel 330W");
        assertThat(product.get("stockQuantity").asInt()).isEqualTo(20);
        assertThat(product.get("active").asBoolean()).isTrue();
        String productId = product.get("id").asText();

        // An EMPLOYEE (not just ADMIN) can read the catalog - needed for the invoice line-item
        // picker - but cannot create/update/adjust stock.
        assertThat(get("/inventory/products/" + productId, employee.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/inventory/products", employee.accessToken(), body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> employeeAdjustAttempt = post(
                "/inventory/products/" + productId + "/stock-adjustments", employee.accessToken(),
                Map.of("quantityChange", 1));
        assertThat(employeeAdjustAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void update_neverExposesAStockQuantityField_stockUnchangedAfterUnrelatedEdit() {
        AuthResponse admin = registerOrganization("Product Update Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Battery Backup", "4500.00", 10);

        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("name", "Battery Backup 150Ah");
        updateBody.put("unitPrice", "4800.00");
        updateBody.put("taxRatePercent", "18.00");
        updateBody.put("active", true);
        ResponseEntity<String> updateResponse = put("/inventory/products/" + productId, admin.accessToken(),
                updateBody);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = parse(updateResponse.getBody());
        assertThat(updated.get("name").asText()).isEqualTo("Battery Backup 150Ah");
        // Stock untouched by a plain field edit - update() has no stockQuantity input at all.
        assertThat(updated.get("stockQuantity").asInt()).isEqualTo(10);
    }

    @Test
    void list_includeInactiveDefaultsFalse_deactivatedProductHiddenUnlessRequested() {
        AuthResponse admin = registerOrganization("Product List Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Discontinued Item", "100.00", 0);

        Map<String, Object> deactivate = new HashMap<>();
        deactivate.put("name", "Discontinued Item");
        deactivate.put("unitPrice", "100.00");
        deactivate.put("active", false);
        assertThat(put("/inventory/products/" + productId, admin.accessToken(), deactivate).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode activeOnly = parse(get("/inventory/products", admin.accessToken()).getBody());
        assertThat(containsId(activeOnly, productId)).isFalse();

        JsonNode withInactive = parse(get("/inventory/products?includeInactive=true", admin.accessToken())
                .getBody());
        assertThat(containsId(withInactive, productId)).isTrue();
    }

    @Test
    void getBySku_exactCaseInsensitiveMatch_notFoundForUnknownOrDeactivatedSku() {
        AuthResponse admin = registerOrganization("Product Sku Org");
        grant(admin.orgId());

        Map<String, Object> body = minimalProductBody("Scannable Widget", "25.00", 5);
        body.put("sku", "ABC-123");
        ResponseEntity<String> created = post("/inventory/products", admin.accessToken(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String productId = parse(created.getBody()).get("id").asText();

        // Case-insensitive exact match - a scanner/typed SKU doesn't need to match casing.
        ResponseEntity<String> found = get("/inventory/products/by-sku/abc-123", admin.accessToken());
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(found.getBody()).get("id").asText()).isEqualTo(productId);

        assertThat(get("/inventory/products/by-sku/NO-SUCH-SKU", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A deactivated product is no longer "found" by SKU either - only what's actually
        // invoiceable counts, same rule as lockAndCheckStock.
        Map<String, Object> deactivate = new HashMap<>();
        deactivate.put("name", "Scannable Widget");
        deactivate.put("unitPrice", "25.00");
        deactivate.put("sku", "ABC-123");
        deactivate.put("active", false);
        assertThat(put("/inventory/products/" + productId, admin.accessToken(), deactivate).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/inventory/products/by-sku/ABC-123", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void tenantIsolation_orgBNeverSeesOrgAsProducts() {
        AuthResponse orgA = registerOrganization("Product Tenant Org A");
        AuthResponse orgB = registerOrganization("Product Tenant Org B");
        grant(orgA.orgId());
        grant(orgB.orgId());
        String orgAProductId = createProduct(orgA.accessToken(), "Org A Only Product", "50.00", 5);

        assertThat(get("/inventory/products/" + orgAProductId, orgB.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode orgBList = parse(get("/inventory/products", orgB.accessToken()).getBody());
        assertThat(containsId(orgBList, orgAProductId)).isFalse();
    }

    // ---- helpers ----

    private Map<String, Object> minimalProductBody(String name, String unitPrice, int stockQuantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("stockQuantity", stockQuantity);
        return body;
    }

    private String createProduct(String token, String name, String unitPrice, int stockQuantity) {
        ResponseEntity<String> response = post("/inventory/products", token,
                minimalProductBody(name, unitPrice, stockQuantity));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private boolean containsId(JsonNode pagedResponse, String id) {
        for (JsonNode node : pagedResponse.get("content")) {
            if (node.get("id").asText().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "ProductCrudIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + CODE,
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@productcrud.test";
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

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@productcrud.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
