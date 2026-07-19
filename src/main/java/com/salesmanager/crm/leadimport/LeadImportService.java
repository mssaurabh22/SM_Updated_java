package com.salesmanager.crm.leadimport;

import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadStatus;
import com.salesmanager.crm.leadimport.dto.LeadImportCommitRequest;
import com.salesmanager.crm.leadimport.dto.LeadImportPreviewResponse;
import com.salesmanager.crm.leadimport.dto.LeadImportResultResponse;
import com.salesmanager.crm.leadimport.dto.LeadImportRowError;
import com.salesmanager.crm.leadimport.dto.LeadImportSkippedDuplicate;
import com.salesmanager.crm.leadimport.parser.CsvSpreadsheetParser;
import com.salesmanager.crm.leadimport.parser.ExcelSpreadsheetParser;
import com.salesmanager.crm.leadimport.parser.SpreadsheetParser;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.masterdata.MasterData;
import com.salesmanager.crm.masterdata.MasterDataRepository;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.CurrentUser;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk-imports an organization's historical/existing clients (from an uploaded .xlsx/.csv
 * file) directly into the Lead table, for Sales/Marketing to build on top of. Deliberately a
 * SEPARATE creation path from LeadService#create, not a wrapper around it - see this class's
 * two entry points' javadoc for exactly why: an interactive single-lead create unconditionally
 * sets status=NEW, always owns the lead to the submitting user, and publishes LeadCreatedEvent
 * (which visit.LeadVisitEventListener reacts to by auto-creating a stub Visit) - none of that
 * is appropriate for hundreds of historical rows being backfilled at once.
 *
 * Two-step flow, preview then commit, both taking the SAME re-uploaded file - no server-side
 * caching/session state is kept between the two calls (simplest-correct, matching this app's
 * established discipline of not introducing state where a stateless re-submit works fine).
 */
@Service
public class LeadImportService {

    /**
     * The only LeadStatus values selectable as {@code defaultStatus} or a per-row status
     * override - LOST is deliberately excluded because that workflow requires a captured Lost
     * Reason (LeadService#updateStatus), which this bulk-import MVP does not collect.
     */
    static final Set<LeadStatus> ALLOWED_IMPORT_STATUSES = EnumSet.of(
            LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.NEGOTIATION,
            LeadStatus.CLOSED_WON, LeadStatus.LAPSED);

    /** The 6 Lead fields backed by the shared master_data table - see resolveMasterField. */
    private static final List<MasterType> MASTER_FIELD_TYPES = List.of(
            MasterType.INDUSTRY, MasterType.BUSINESS_TYPE, MasterType.LEAD_SOURCE,
            MasterType.DESIGNATION, MasterType.STATE, MasterType.CITY);

    private static final int PREVIEW_ROW_LIMIT = 10;

    private final LeadRepository leadRepository;
    private final MasterDataRepository masterDataRepository;
    private final EmployeeRepository employeeRepository;
    private final ActivityLogService activityLogService;
    private final CurrentUser currentUser;
    private final ExcelSpreadsheetParser excelSpreadsheetParser;
    private final CsvSpreadsheetParser csvSpreadsheetParser;

    public LeadImportService(LeadRepository leadRepository,
                              MasterDataRepository masterDataRepository,
                              EmployeeRepository employeeRepository,
                              ActivityLogService activityLogService,
                              CurrentUser currentUser,
                              ExcelSpreadsheetParser excelSpreadsheetParser,
                              CsvSpreadsheetParser csvSpreadsheetParser) {
        this.leadRepository = leadRepository;
        this.masterDataRepository = masterDataRepository;
        this.employeeRepository = employeeRepository;
        this.activityLogService = activityLogService;
        this.currentUser = currentUser;
        this.excelSpreadsheetParser = excelSpreadsheetParser;
        this.csvSpreadsheetParser = csvSpreadsheetParser;
    }

    /**
     * Parses the header row + first {@value #PREVIEW_ROW_LIMIT} data rows only, and
     * auto-suggests a columnMapping (see LeadImportField#suggestMapping) - the admin
     * reviews/edits this before ever calling commit(). No DB writes here at all.
     */
    @Transactional(readOnly = true, noRollbackFor = UnsupportedImportFileException.class)
    public LeadImportPreviewResponse preview(MultipartFile file) {
        List<List<String>> rows = parseFile(file);
        if (rows.isEmpty()) {
            return new LeadImportPreviewResponse(List.of(), List.of(), Map.of(), 0);
        }

        List<String> headers = rows.get(0);
        List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
        List<List<String>> previewRows = dataRows.size() > PREVIEW_ROW_LIMIT
                ? dataRows.subList(0, PREVIEW_ROW_LIMIT)
                : dataRows;

        return new LeadImportPreviewResponse(headers, previewRows, LeadImportField.suggestMapping(headers),
                dataRows.size());
    }

    /**
     * Parses EVERY data row (no preview cap) using the admin-confirmed columnMapping, and
     * creates a Lead for each row that is neither missing a required field nor a duplicate of
     * an existing Lead in this org. Every successfully-created row: ownerId = defaultOwnerId
     * (who is responsible for follow-up going forward); createdBy = the IMPORTING ADMIN's own
     * employee id, not defaultOwnerId - deliberately different from LeadService#create (where
     * the submitter and the owner are normally the same person) since a bulk historical import
     * is very often done by an admin on behalf of a rep who wasn't the one uploading the file;
     * this keeps an accurate "who actually performed this data entry" audit trail. Publishes NO
     * LeadCreatedEvent - the key behavioral difference from LeadService#create - so no stub
     * Visit is ever auto-created for a backfilled historical row.
     */
    @Transactional(noRollbackFor = {UnsupportedImportFileException.class, InvalidReferenceException.class})
    public LeadImportResultResponse commit(MultipartFile file, LeadImportCommitRequest request) {
        validateDefaultStatus(request.defaultStatus());
        Employee owner = employeeRepository.findById(request.defaultOwnerId())
                .filter(Employee::isActive)
                .orElseThrow(() -> new InvalidReferenceException("defaultOwnerId",
                        "defaultOwnerId does not reference an active employee in this organization"));

        List<List<String>> rows = parseFile(file);
        List<List<String>> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
        if (dataRows.isEmpty()) {
            return new LeadImportResultResponse(0, 0, 0, 0, List.of(), List.of());
        }

        Map<String, Integer> columnMapping = request.columnMapping() != null ? request.columnMapping() : Map.of();

        // Batch-fetch every relevant MasterType's active rows ONCE for the whole import (not
        // per row) - same "batch-fetch, don't N+1" discipline as attendance.AttendanceService.
        Map<MasterType, Map<String, UUID>> masterLabelMaps = MASTER_FIELD_TYPES.stream()
                .collect(Collectors.toMap(type -> type, this::loadLabelMap));

        UUID importingAdminId = currentUser.get().getEmployeeId();

        List<LeadImportRowError> errors = new ArrayList<>();
        List<LeadImportSkippedDuplicate> skippedDuplicates = new ArrayList<>();
        int importedCount = 0;

        for (int i = 0; i < dataRows.size(); i++) {
            // +1 for the 0-based loop index, +1 more for the header row itself being row 1 -
            // see LeadImportRowError's javadoc for why this matches the file's own row numbers.
            int rowNumber = i + 2;
            List<String> row = dataRows.get(i);
            try {
                String companyName = cell(row, columnMapping, LeadImportField.COMPANY_NAME);
                String contactPerson = cell(row, columnMapping, LeadImportField.CONTACT_PERSON);
                String contactNo = cell(row, columnMapping, LeadImportField.CONTACT_NO);

                List<String> missingFields = new ArrayList<>();
                if (companyName.isBlank()) {
                    missingFields.add("companyName");
                }
                if (contactPerson.isBlank()) {
                    missingFields.add("contactPerson");
                }
                if (contactNo.isBlank()) {
                    missingFields.add("contactNo");
                }
                if (!missingFields.isEmpty()) {
                    errors.add(new LeadImportRowError(rowNumber,
                            "Missing required field(s): " + String.join(", ", missingFields)));
                    continue;
                }

                List<Lead> duplicates = leadRepository.findByContactNoOrCompanyNameIgnoreCase(contactNo, companyName);
                if (!duplicates.isEmpty()) {
                    skippedDuplicates.add(new LeadImportSkippedDuplicate(
                            rowNumber, companyName, duplicates.get(0).getId()));
                    continue;
                }

                Lead lead = buildLead(row, columnMapping, masterLabelMaps, request.defaultStatus(),
                        companyName, contactPerson, contactNo, owner.getId(), importingAdminId);
                // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
                Lead saved = leadRepository.saveAndFlush(lead);
                activityLogService.record(saved.getId(), saved.getOwnerId(), saved.getCompanyName(),
                        ActivityType.LEAD_CREATED, importingAdminId, "Lead created via bulk import");
                importedCount++;
            } catch (Exception e) {
                // Last-resort safety net so one unexpected bad row (e.g. a value that doesn't
                // fit a column's DB type) never aborts the rest of the batch - every field this
                // method itself builds is validated/defaulted defensively before ever reaching
                // saveAndFlush, so this should rarely if ever fire in practice.
                errors.add(new LeadImportRowError(rowNumber, "Unexpected error: " + e.getMessage()));
            }
        }

        return new LeadImportResultResponse(dataRows.size(), importedCount, skippedDuplicates.size(), errors.size(),
                skippedDuplicates, errors);
    }

    private void validateDefaultStatus(LeadStatus status) {
        if (status == null || !ALLOWED_IMPORT_STATUSES.contains(status)) {
            throw new InvalidReferenceException("defaultStatus",
                    "defaultStatus must be one of " + ALLOWED_IMPORT_STATUSES
                            + " - LOST is not supported for bulk import (it requires a captured Lost Reason)");
        }
    }

    private Lead buildLead(List<String> row, Map<String, Integer> columnMapping,
                            Map<MasterType, Map<String, UUID>> masterLabelMaps, LeadStatus defaultStatus,
                            String companyName, String contactPerson, String contactNo,
                            UUID ownerId, UUID createdBy) {
        String email = blankToNull(cell(row, columnMapping, LeadImportField.EMAIL));
        BigDecimal turnover = parseTurnoverLeniently(cell(row, columnMapping, LeadImportField.TURNOVER));
        String requirements = blankToNull(cell(row, columnMapping, LeadImportField.REQUIREMENTS));
        String currentProductSolution = blankToNull(cell(row, columnMapping, LeadImportField.CURRENT_PRODUCT_SOLUTION));
        String budgetRange = blankToNull(cell(row, columnMapping, LeadImportField.BUDGET_RANGE));
        String remarks = blankToNull(cell(row, columnMapping, LeadImportField.REMARKS));

        MasterFieldResolution industry = resolveMasterField(
                cell(row, columnMapping, LeadImportField.INDUSTRY), masterLabelMaps.get(MasterType.INDUSTRY));
        MasterFieldResolution businessType = resolveMasterField(
                cell(row, columnMapping, LeadImportField.BUSINESS_TYPE), masterLabelMaps.get(MasterType.BUSINESS_TYPE));
        MasterFieldResolution leadSource = resolveMasterField(
                cell(row, columnMapping, LeadImportField.LEAD_SOURCE), masterLabelMaps.get(MasterType.LEAD_SOURCE));
        MasterFieldResolution designation = resolveMasterField(
                cell(row, columnMapping, LeadImportField.DESIGNATION), masterLabelMaps.get(MasterType.DESIGNATION));
        MasterFieldResolution state = resolveMasterField(
                cell(row, columnMapping, LeadImportField.STATE), masterLabelMaps.get(MasterType.STATE));
        // Deliberately NOT cross-checking city's parent_id against state here (unlike
        // LeadService's interactive creatable-field validation via MasterDataService#
        // validateReference's 4-arg overload) - a documented simplification for bulk
        // historical data, where the uploaded city/state text is often inconsistent/partial
        // and shouldn't sink an otherwise-good row over a mismatched pair.
        MasterFieldResolution city = resolveMasterField(
                cell(row, columnMapping, LeadImportField.CITY), masterLabelMaps.get(MasterType.CITY));

        LeadStatus status = resolveRowStatus(cell(row, columnMapping, LeadImportField.STATUS), defaultStatus);

        return Lead.builder()
                .companyName(companyName)
                .contactPerson(contactPerson)
                .contactNo(contactNo)
                .email(email)
                .turnover(turnover)
                .requirements(requirements)
                .currentProductSolution(currentProductSolution)
                .budgetRange(budgetRange)
                .remarks(remarks)
                .industryId(industry.id())
                .industryOther(industry.other())
                .businessTypeId(businessType.id())
                .businessTypeOther(businessType.other())
                .leadSourceId(leadSource.id())
                .leadSourceOther(leadSource.other())
                .designationId(designation.id())
                .designationOther(designation.other())
                .stateId(state.id())
                .stateOther(state.other())
                .cityId(city.id())
                .cityOther(city.other())
                .status(status)
                .ownerId(ownerId)
                .createdBy(createdBy)
                .build();
    }

    /**
     * Resolves one master-driven cell value against this org's pre-fetched label map for that
     * MasterType: a case-insensitive exact label match uses the existing row's id; a blank
     * cell (or an unmapped column) leaves both id and other null; anything else - a value that
     * doesn't match any existing label - becomes the free-text *Other fallback. A typed value
     * is NEVER auto-promoted into master data, per this codebase's established rule (see
     * Lead#industryOther's javadoc) - it stays a one-off note on this imported row only.
     */
    private MasterFieldResolution resolveMasterField(String rawValue, Map<String, UUID> labelMap) {
        if (rawValue.isBlank()) {
            return new MasterFieldResolution(null, null);
        }
        UUID id = labelMap.get(rawValue.toLowerCase(Locale.ROOT));
        if (id != null) {
            return new MasterFieldResolution(id, null);
        }
        return new MasterFieldResolution(null, rawValue);
    }

    private Map<String, UUID> loadLabelMap(MasterType type) {
        return masterDataRepository.findByTypeAndActive(type, true, Sort.unsorted()).stream()
                .collect(Collectors.toMap(
                        m -> m.getLabel().toLowerCase(Locale.ROOT),
                        MasterData::getId,
                        (firstId, secondId) -> firstId));
    }

    /**
     * A per-row status cell only overrides defaultStatus if it case-insensitively matches one
     * of the same ALLOWED_IMPORT_STATUSES the batch default itself is restricted to (so a typo'd
     * or LOST cell value can never sneak past the "no LOST in bulk import" rule via a per-row
     * override) - an unmatched/blank cell silently falls back to defaultStatus, never an error.
     */
    private LeadStatus resolveRowStatus(String rawStatus, LeadStatus defaultStatus) {
        if (rawStatus.isBlank()) {
            return defaultStatus;
        }
        for (LeadStatus candidate : ALLOWED_IMPORT_STATUSES) {
            if (candidate.name().equalsIgnoreCase(rawStatus)) {
                return candidate;
            }
        }
        return defaultStatus;
    }

    /**
     * Turnover is optional free-form spreadsheet data (currency symbols/commas/blank cells are
     * all realistic) - an unparseable value is silently treated as "not supplied" (null) rather
     * than failing the whole row, consistent with this feature's general leniency (only
     * companyName/contactPerson/contactNo are hard-required per the spec).
     */
    private BigDecimal parseTurnoverLeniently(String rawValue) {
        if (rawValue.isBlank()) {
            return null;
        }
        String cleaned = rawValue.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    /** Returns "" (never null) for an unmapped field, an out-of-range column, or a blank cell. */
    private String cell(List<String> row, Map<String, Integer> columnMapping, LeadImportField field) {
        Integer index = columnMapping.get(field.key());
        if (index == null || index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    private List<List<String>> parseFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImportFileException("No file was uploaded");
        }
        String filename = file.getOriginalFilename();
        String lowerCaseFilename = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        SpreadsheetParser parser;
        if (lowerCaseFilename.endsWith(".xlsx")) {
            parser = excelSpreadsheetParser;
        } else if (lowerCaseFilename.endsWith(".csv")) {
            parser = csvSpreadsheetParser;
        } else {
            throw new UnsupportedImportFileException(
                    "Unsupported file type - only .xlsx and .csv files are supported (got '" + filename + "')");
        }

        try {
            return parser.parse(file);
        } catch (IOException e) {
            throw new UnsupportedImportFileException("Could not parse the uploaded file: " + e.getMessage());
        }
    }

    /** id XOR other, per the established creatable-field pairing - see Lead#industryOther's javadoc. */
    private record MasterFieldResolution(UUID id, String other) {
    }
}
