package com.salesmanager.crm.visit.dto;

import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitType;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight projection returned by GET /visits/same-day - deliberately not a full
 * VisitResponse, since the caller only needs enough to decide "is there already a visit
 * scheduled/logged for this lead on this day?" before proceeding with (or abandoning) their
 * own create. Same shape/rationale as lead.dto.LeadDuplicateMatch.
 *
 * purposeId/purposeOther are returned raw (ids, not resolved display labels) - same
 * "backend returns ids, frontend resolves display names" split already established for master
 * data throughout this app; looking up the VISIT_PURPOSE label server-side here would be a
 * layering violation, not a convenience.
 */
public record VisitSameDayMatch(
        UUID id,
        LocalDate visitDate,
        VisitStatus status,
        VisitType visitType,
        UUID purposeId,
        String purposeOther) {

    public static VisitSameDayMatch from(Visit visit) {
        return new VisitSameDayMatch(
                visit.getId(), visit.getVisitDate(), visit.getStatus(), visit.getVisitType(),
                visit.getPurposeId(), visit.getPurposeOther());
    }
}
