package com.salesmanager.crm.lead.dto;

import com.salesmanager.crm.lead.Lead;
import java.util.UUID;

/**
 * Lightweight projection returned by GET /leads/duplicates - deliberately not a full
 * LeadResponse, since the caller only needs enough to decide "is this actually the same
 * lead?" before proceeding with (or abandoning) their own create.
 */
public record LeadDuplicateMatch(
        UUID id,
        String companyName,
        String contactPerson,
        String contactNo,
        UUID ownerId) {

    public static LeadDuplicateMatch from(Lead lead) {
        return new LeadDuplicateMatch(
                lead.getId(), lead.getCompanyName(), lead.getContactPerson(), lead.getContactNo(), lead.getOwnerId());
    }
}
