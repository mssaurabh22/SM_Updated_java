package com.salesmanager.crm.lead;

import java.util.UUID;

/**
 * Optional query filters for {@code GET /leads}, as requested by the caller. The
 * EMPLOYEE-vs-ADMIN ownership visibility rule (an EMPLOYEE's ownerId filter is always
 * silently forced to their own id) is enforced in LeadService#list, not here - this record
 * only carries what was literally asked for.
 *
 * <p>{@code search} is a free-text term matched (case-insensitive, substring) against
 * companyName/contactPerson/contactNo/email - added for the "find an existing lead to log a
 * visit against" picker, which needs real server-side search (company/contact/phone/email)
 * rather than the Lead list page's existing client-side-over-the-loaded-page filtering, which
 * only covers the current page and doesn't search email at all.
 */
public record LeadFilter(LeadStatus status, UUID ownerId, UUID interestLevelId, String search) {
}
