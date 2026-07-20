package com.salesmanager.crm.visit;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as LeadRepository/EmployeeRepository - no
 * manual "WHERE organizationId = ..." here. JpaSpecificationExecutor backs VisitService#list's
 * dynamic leadId/status/date-range filter combinations (see VisitSpecifications).
 */
public interface VisitRepository extends JpaRepository<Visit, UUID>, JpaSpecificationExecutor<Visit> {

    /**
     * Backs VisitService#getTodaysFollowUps - all PLANNED visits due today-or-earlier across
     * the whole tenant; the service further filters this down to the current user's own
     * leads (a personal daily agenda, not an org-wide report - even an ADMIN only sees their
     * own via this endpoint).
     */
    List<Visit> findByVisitDateLessThanEqualAndStatus(LocalDate date, VisitStatus status, Sort sort);

    /**
     * Backs LeadVisitEventListener#onFollowUpScheduled's duplicate-stub-Visit guard - true if
     * ANY Visit (regardless of status; PLANNED or COMPLETED both count as "already something
     * scheduled/done that day") already exists for this lead on this date, so the auto-stub
     * creation there can no-op instead of double-booking the lead.
     */
    boolean existsByLeadIdAndVisitDate(UUID leadId, LocalDate visitDate);

    /**
     * Backs VisitService#checkSameDay - the non-blocking same-day-visit advisory check (GET
     * /visits/same-day). Returns every Visit (any status) already on the books for this
     * lead+date, for the caller to surface as a "heads up, this lead already has a visit that
     * day" warning before submitting a new one - purely advisory, nothing here blocks or
     * changes VisitService#create's own behavior.
     */
    List<Visit> findByLeadIdAndVisitDate(UUID leadId, LocalDate visitDate);

    /**
     * Backs ReportingService#visitsCompletedVsMissed - one GROUP BY query for the whole
     * COMPLETED/MISSED/PLANNED breakdown, optionally restricted to an inclusive visitDate
     * range. Either bound may be null (covers "all visits" on that side of the range) - each
     * bound is compared via {@code COALESCE(:param, v.visitDate)} rather than a
     * {@code :param IS NULL OR ...} branch: a null bound coalesces to v.visitDate itself,
     * making that side of the comparison trivially true (v.visitDate >= v.visitDate), so one
     * query still serves all three cases (no range, half-open range, closed range) without
     * branching. This also sidesteps a real Postgres limitation with the more obvious
     * {@code :param IS NULL OR ...} form: when both dateFrom/dateTo are null, Postgres's
     * extended query protocol cannot infer a bind parameter's type from an "IS NULL"-only
     * context and rejects the prepared statement with "could not determine data type of
     * parameter" (SQLState 42P18) - COALESCE against the (typed) visitDate column always gives
     * the parameter unambiguous type context. JPQL (not native SQL) so the Hibernate
     * {@code tenantFilter} - which only rewrites HQL/Criteria queries, not native SQL - is
     * guaranteed to apply, same org-scoping as every other query in this repository.
     */
    @Query("SELECT v.status AS status, COUNT(v) AS count FROM Visit v "
            + "WHERE v.visitDate >= COALESCE(:dateFrom, v.visitDate) "
            + "AND v.visitDate <= COALESCE(:dateTo, v.visitDate) "
            + "GROUP BY v.status")
    List<VisitStatusCount> countGroupedByStatus(@Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * Team-visibility (FeatureEntitlement.TEAM_VISIBILITY) counterpart to
     * {@link #countGroupedByStatus(LocalDate, LocalDate)} - restricted to visits under a given
     * set of lead ids (the caller resolves those via LeadRepository#findByOwnerIdIn against a
     * manager's scope) rather than every visit in the org. Same COALESCE-bound rationale as the
     * unscoped query above.
     */
    @Query("SELECT v.status AS status, COUNT(v) AS count FROM Visit v "
            + "WHERE v.leadId IN :leadIds "
            + "AND v.visitDate >= COALESCE(:dateFrom, v.visitDate) "
            + "AND v.visitDate <= COALESCE(:dateTo, v.visitDate) "
            + "GROUP BY v.status")
    List<VisitStatusCount> countGroupedByStatusForLeadIds(@Param("leadIds") Set<UUID> leadIds,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);
}
