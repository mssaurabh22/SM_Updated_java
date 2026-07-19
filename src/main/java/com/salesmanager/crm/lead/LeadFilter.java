package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Optional query filters for {@code GET /leads}, as requested by the caller. The
 * EMPLOYEE-vs-ADMIN ownership visibility rule (an EMPLOYEE's ownerId filter is always
 * silently forced to their own id) is enforced in LeadService#list, not here - this record
 * only carries what was literally asked for.
 */
public record LeadFilter(LeadStatus status, UUID ownerId, UUID interestLevelId) {
}
