package com.salesmanager.crm.visit;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Optional query filters for {@code GET /visits}, as requested by the caller. The
 * EMPLOYEE-forced-to-own-leads'-visits visibility rule is enforced in VisitService#list, not
 * here - this record only carries what was literally asked for.
 */
public record VisitFilter(UUID leadId, VisitStatus status, LocalDate dateFrom, LocalDate dateTo) {
}
