package com.salesmanager.crm.leadimport.dto;

import java.util.List;

/**
 * Response for {@code POST /leads/import/commit}. {@code totalRows} is every data row parsed
 * (header excluded, no cap - unlike the preview step); {@code importedCount +
 * skippedDuplicateCount + errorCount} always equals {@code totalRows}, since every row lands
 * in exactly one of those three buckets.
 */
public record LeadImportResultResponse(
        int totalRows,
        int importedCount,
        int skippedDuplicateCount,
        int errorCount,
        List<LeadImportSkippedDuplicate> skippedDuplicates,
        List<LeadImportRowError> errors) {
}
