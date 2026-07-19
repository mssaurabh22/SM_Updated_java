package com.salesmanager.crm.leadimport.dto;

import java.util.UUID;

/**
 * One row skipped because it matched an existing Lead in this org via the same
 * contactNo-or-companyName check as {@code GET /leads/duplicates}
 * (LeadRepository#findByContactNoOrCompanyNameIgnoreCase) - see LeadImportRowError's javadoc
 * for the {@code rowNumber} convention. {@code existingLeadId} references the Lead already in
 * the system that this row was judged a duplicate of.
 */
public record LeadImportSkippedDuplicate(int rowNumber, String companyName, UUID existingLeadId) {
}
