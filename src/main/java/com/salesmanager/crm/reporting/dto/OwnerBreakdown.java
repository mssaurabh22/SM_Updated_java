package com.salesmanager.crm.reporting.dto;

import java.util.UUID;

/**
 * One row of the {@code byOwner} breakdown in PipelineSummaryResponse - one entry per
 * employee who owns at least one lead in the org (an employee owning zero leads simply
 * doesn't appear, unlike the always-fully-populated byStatus map).
 */
public record OwnerBreakdown(UUID ownerId, String ownerName, long leadCount, long closedWonCount) {
}
