package com.salesmanager.crm.activity;

import java.util.UUID;

/**
 * Optional query filters for {@code GET /activity}, as requested by the caller. The
 * EMPLOYEE-forced-to-own-leads visibility rule (an EMPLOYEE's ownerId filter is always
 * silently forced to their own id) is enforced in ActivityLogService#list, not here - this
 * record only carries what was literally asked for.
 */
public record ActivityFilter(UUID leadId, UUID ownerId, ActivityType type) {
}
