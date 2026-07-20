package com.salesmanager.crm.common.event;

import java.util.UUID;

/**
 * Published by {@code LeadService#create} right after the new Lead is saved. Lives in this
 * neutral {@code common.event} package (rather than under {@code lead}) so both the
 * publishing side ({@code lead}) and the listening side ({@code visit}) can depend on it
 * without an awkward package-direction dependency.
 *
 * {@code logAsVisitToday} mirrors the request flag of the same name on
 * {@code LeadCreateRequest}: when true, the touchpoint that led to creating this Lead is
 * itself logged as a completed Visit dated today (see {@code visit.LeadVisitEventListener}).
 *
 * {@code visitType} is the raw "FIELD"/"TELEPHONIC" string from {@code LeadCreateRequest}
 * (nullable - defaults to "FIELD" on the listening side), only meaningful when
 * {@code logAsVisitToday} is true - kept as a plain String here (not {@code visit.VisitType})
 * for the same package-direction reason as this record's own placement.
 */
public record LeadCreatedEvent(UUID leadId, UUID organizationId, boolean logAsVisitToday, String visitType) {
}
