package com.salesmanager.crm.leadimport.dto;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /leads/import/preview}. {@code suggestedMapping} keys are
 * {@link com.salesmanager.crm.leadimport.LeadImportField#key()} values (e.g. "companyName"),
 * values are 0-based column indices into {@code headers}/each row of {@code previewRows} - a
 * field with no confident header match is simply absent from the map (never -1). {@code
 * previewRows} is capped at the first 10 data rows (header excluded) so the admin can eyeball
 * the mapping before committing; {@code totalDataRowCount} is the FULL data-row count (not
 * capped) so the frontend can show "N rows will be imported" ahead of the actual commit.
 */
public record LeadImportPreviewResponse(
        List<String> headers,
        List<List<String>> previewRows,
        Map<String, Integer> suggestedMapping,
        int totalDataRowCount) {
}
