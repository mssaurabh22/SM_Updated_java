package com.salesmanager.crm.leadimport.dto;

import com.salesmanager.crm.lead.LeadStatus;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * The JSON part ("request") accompanying the re-uploaded file on {@code POST
 * /leads/import/commit} - see LeadImportController's javadoc for why the same file is
 * re-uploaded rather than cached server-side between preview and commit.
 *
 * {@code columnMapping} keys are {@link com.salesmanager.crm.leadimport.LeadImportField#key()}
 * values, values are 0-based column indices - normally the (possibly admin-edited) mapping
 * echoed back from the preview step, but a null/missing entry for a given field is treated as
 * "not mapped" (that field is left blank/{@code *Other}-less for every row) rather than an
 * error. {@code defaultStatus} is deliberately restricted (validated in
 * LeadImportService#validateDefaultStatus, not here, since it depends on the fixed
 * ALLOWED_IMPORT_STATUSES set) to NEW/CONTACTED/NEGOTIATION/CLOSED_WON/LAPSED - LOST is excluded
 * because that workflow requires a captured Lost Reason, which this bulk-import MVP does not
 * collect.
 */
public record LeadImportCommitRequest(
        Map<String, Integer> columnMapping,

        @NotNull(message = "defaultOwnerId is required")
        UUID defaultOwnerId,

        @NotNull(message = "defaultStatus is required")
        LeadStatus defaultStatus) {
}
