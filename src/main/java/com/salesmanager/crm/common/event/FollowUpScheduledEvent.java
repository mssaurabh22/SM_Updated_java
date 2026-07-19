package com.salesmanager.crm.common.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published whenever a next-follow-up date is set (or changed to a new, non-null value) on
 * either a Lead ({@code nextFollowupDate}) or a Visit ({@code nextVisitDate}). A listener in
 * the {@code visit} package reacts by creating a minimal stub Visit dated {@code followUpDate}
 * - never a clone of the triggering record's other fields.
 *
 * {@code carriedOverPurposeId} is nullable: populated with the originating Visit's own
 * {@code purposeId} when this event originates from a Visit's {@code nextVisitDate}; left
 * null when it originates from a Lead's {@code nextFollowupDate}, which has no visit/purpose
 * context yet.
 */
public record FollowUpScheduledEvent(UUID leadId, UUID organizationId, LocalDate followUpDate,
                                      UUID carriedOverPurposeId) {
}
