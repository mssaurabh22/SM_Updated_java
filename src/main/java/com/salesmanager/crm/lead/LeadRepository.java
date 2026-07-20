package com.salesmanager.crm.lead;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as EmployeeRepository/MasterDataRepository -
 * no manual "WHERE organizationId = ..." here. JpaSpecificationExecutor backs LeadService#list's
 * dynamic status/owner/interestLevel filter combinations (see LeadSpecifications) instead of a
 * combinatorial pile of derived query methods.
 */
public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

    /**
     * Backs the duplicate-lead check (LeadService#checkDuplicates). A null argument is
     * translated by Spring Data's Criteria-based query derivation into an "IS NULL" predicate
     * for that side of the OR, so callers may pass either contactNo or companyName alone.
     */
    List<Lead> findByContactNoOrCompanyNameIgnoreCase(String contactNo, String companyName);

    /**
     * Backs VisitService's ownership-via-parent-lead visibility rule (an EMPLOYEE sees
     * Visits only for leads they own) and its personal "today's follow-ups" agenda - both
     * need the set of lead ids a given employee owns.
     */
    List<Lead> findByOwnerId(UUID ownerId);

    /**
     * Team-visibility (FeatureEntitlement.TEAM_VISIBILITY) counterpart to {@link #findByOwnerId} -
     * backs VisitService#list and ReportingService#visitsCompletedVsMissed's scoped variant when
     * a manager's visible owners are themself + every subordinate, not just a single id.
     */
    List<Lead> findByOwnerIdIn(Set<UUID> ownerIds);

    /**
     * Backs ReportingService#pipelineSummary/#conversionRate - a single GROUP BY query
     * (rather than one count() call per LeadStatus) so the whole byStatus breakdown is one
     * round trip. Statuses with zero leads simply produce no row here; ReportingService fills
     * those in as explicit zero-count buckets. This is JPQL (not native SQL) specifically so
     * the Hibernate {@code tenantFilter} - which only rewrites HQL/Criteria queries, not native
     * SQL - is guaranteed to apply, same org-scoping as every other query in this repository.
     */
    @Query("SELECT l.status AS status, COUNT(l) AS count FROM Lead l GROUP BY l.status")
    List<LeadStatusCount> countGroupedByStatus();

    /**
     * Backs the {@code byOwner} breakdown in ReportingService#pipelineSummary - one row per
     * distinct ownerId with at least one lead in the org, giving both their total lead count
     * and how many of those are CLOSED_WON. Also JPQL, not native SQL, for the same
     * tenantFilter-applicability reason as {@link #countGroupedByStatus()}.
     */
    @Query("SELECT l.ownerId AS ownerId, COUNT(l) AS leadCount, "
            + "SUM(CASE WHEN l.status = com.salesmanager.crm.lead.LeadStatus.CLOSED_WON THEN 1L ELSE 0L END) AS closedWonCount "
            + "FROM Lead l GROUP BY l.ownerId")
    List<LeadOwnerCount> countGroupedByOwner();

    /**
     * Team-visibility (FeatureEntitlement.TEAM_VISIBILITY) counterpart to
     * {@link #countGroupedByStatus()} - restricts the same status breakdown to a manager's
     * scope (themself + every subordinate) instead of the whole org, for ReportingService's
     * manager-scoped pipeline-summary/conversion-rate variants.
     */
    @Query("SELECT l.status AS status, COUNT(l) AS count FROM Lead l "
            + "WHERE l.ownerId IN :ownerIds GROUP BY l.status")
    List<LeadStatusCount> countGroupedByStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds);
}
