package com.salesmanager.crm.reporting;

import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadOwnerCount;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadStatus;
import com.salesmanager.crm.lead.LeadStatusCount;
import com.salesmanager.crm.reporting.dto.ConversionRateResponse;
import com.salesmanager.crm.reporting.dto.OwnerBreakdown;
import com.salesmanager.crm.reporting.dto.PipelineSummaryResponse;
import com.salesmanager.crm.reporting.dto.VisitsCompletedVsMissedResponse;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import com.salesmanager.crm.visit.VisitRepository;
import com.salesmanager.crm.visit.VisitStatusCount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregate queries for the Phase 5 reporting/dashboard endpoints. Every query here
 * goes through LeadRepository/VisitRepository/EmployeeRepository's normal EntityManager, so the
 * Hibernate {@code tenantFilter} that's already active for the current request (see
 * TenantSessionManager) scopes these aggregates to the current org exactly like every other
 * read in the codebase - no manual "WHERE organizationId = ..." here either.
 *
 * <p>ADMIN always gets the unrestricted, org-wide picture. An EMPLOYEE only reaches these
 * methods at all when TEAM_VISIBILITY is entitled AND they have at least one subordinate (see
 * {@link #resolveOwnerScope()}) - a plain individual contributor still gets a 403, same as
 * before this feature existed. That manager's aggregates are then scoped to themself + their
 * whole subordinate chain, never the full org.
 */
@Service
public class ReportingService {

    private final LeadRepository leadRepository;
    private final VisitRepository visitRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final CurrentUser currentUser;

    public ReportingService(LeadRepository leadRepository, VisitRepository visitRepository,
                             EmployeeRepository employeeRepository,
                             EmployeeHierarchyService employeeHierarchyService,
                             CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.visitRepository = visitRepository;
        this.employeeRepository = employeeRepository;
        this.employeeHierarchyService = employeeHierarchyService;
        this.currentUser = currentUser;
    }

    /**
     * byStatus always contains every LeadStatus value (pre-seeded to zero below, then
     * overwritten for statuses that actually have leads) so the frontend gets a consistent set
     * of categories to render regardless of which ones happen to be empty in this org.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public PipelineSummaryResponse pipelineSummary() {
        Set<UUID> ownerScope = resolveOwnerScope();
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            byStatus.put(status, 0L);
        }
        long totalLeads = 0;
        List<LeadStatusCount> statusRows = ownerScope == null
                ? leadRepository.countGroupedByStatus()
                : leadRepository.countGroupedByStatusForOwners(ownerScope);
        for (LeadStatusCount row : statusRows) {
            byStatus.put(row.getStatus(), row.getCount());
            totalLeads += row.getCount();
        }

        List<LeadOwnerCount> ownerCounts = leadRepository.countGroupedByOwner().stream()
                .filter(row -> ownerScope == null || ownerScope.contains(row.getOwnerId()))
                .toList();
        Set<UUID> ownerIds = ownerCounts.stream().map(LeadOwnerCount::getOwnerId).collect(Collectors.toSet());
        Map<UUID, String> ownerNamesById = employeeRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName));

        List<OwnerBreakdown> byOwner = ownerCounts.stream()
                .map(row -> new OwnerBreakdown(
                        row.getOwnerId(),
                        ownerNamesById.getOrDefault(row.getOwnerId(), "Unknown"),
                        row.getLeadCount(),
                        row.getClosedWonCount()))
                .toList();

        return new PipelineSummaryResponse(byStatus, totalLeads, byOwner);
    }

    /**
     * Reuses the same status-count aggregate as pipelineSummary() rather than a raw SQL
     * division, so the totalLeads==0 case and the 2-decimal rounding are both explicit,
     * ordinary Java rather than baked into a query.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public ConversionRateResponse conversionRate() {
        Set<UUID> ownerScope = resolveOwnerScope();
        long totalLeads = 0;
        long closedWonCount = 0;
        long lostCount = 0;
        List<LeadStatusCount> statusRows = ownerScope == null
                ? leadRepository.countGroupedByStatus()
                : leadRepository.countGroupedByStatusForOwners(ownerScope);
        for (LeadStatusCount row : statusRows) {
            totalLeads += row.getCount();
            if (row.getStatus() == LeadStatus.CLOSED_WON) {
                closedWonCount = row.getCount();
            } else if (row.getStatus() == LeadStatus.LOST) {
                lostCount = row.getCount();
            }
        }

        double conversionRatePercent = totalLeads == 0
                ? 0.0
                : roundToTwoDecimals(closedWonCount * 100.0 / totalLeads);

        return new ConversionRateResponse(totalLeads, closedWonCount, lostCount, conversionRatePercent);
    }

    /**
     * dateFrom/dateTo are both optional and independently nullable (VisitRepository's query
     * handles a null bound as "no restriction on that side"). completionRatePercent's
     * denominator is completed+missed only - PLANNED visits are excluded since they haven't
     * resolved to either outcome yet.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public VisitsCompletedVsMissedResponse visitsCompletedVsMissed(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        long completed = 0;
        long missed = 0;
        long planned = 0;
        List<VisitStatusCount> statusRows;
        if (ownerScope == null) {
            statusRows = visitRepository.countGroupedByStatus(dateFrom, dateTo);
        } else {
            Set<UUID> leadIds = leadRepository.findByOwnerIdIn(ownerScope).stream()
                    .map(Lead::getId)
                    .collect(Collectors.toSet());
            statusRows = leadIds.isEmpty()
                    ? List.of()
                    : visitRepository.countGroupedByStatusForLeadIds(leadIds, dateFrom, dateTo);
        }
        for (VisitStatusCount row : statusRows) {
            switch (row.getStatus()) {
                case COMPLETED -> completed = row.getCount();
                case MISSED -> missed = row.getCount();
                case PLANNED -> planned = row.getCount();
            }
        }

        long resolved = completed + missed;
        double completionRatePercent = resolved == 0
                ? 0.0
                : roundToTwoDecimals(completed * 100.0 / resolved);

        return new VisitsCompletedVsMissedResponse(completed, missed, planned, completionRatePercent);
    }

    /**
     * {@code null} means "unrestricted" (ADMIN - org-wide aggregates). For an EMPLOYEE, this is
     * themself + every subordinate at any depth via TEAM_VISIBILITY
     * (EmployeeHierarchyService#getTeamVisibilityScope) - and if that scope comes back empty
     * (entitlement off, or this employee simply has no reports), reports stay exactly as
     * inaccessible to them as before this feature existed: an AccessDeniedException, which
     * GlobalExceptionHandler maps to a 403.
     */
    private Set<UUID> resolveOwnerScope() {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() != Role.EMPLOYEE) {
            return null;
        }
        Set<UUID> subordinateIds = employeeHierarchyService
                .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
        if (subordinateIds.isEmpty()) {
            throw new AccessDeniedException("Reports are limited to Admins and managers with team visibility");
        }
        Set<UUID> scope = new HashSet<>(subordinateIds);
        scope.add(principal.getEmployeeId());
        return scope;
    }

    private static double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
