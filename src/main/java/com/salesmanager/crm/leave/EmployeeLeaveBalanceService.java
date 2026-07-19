package com.salesmanager.crm.leave;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.leave.dto.LeaveBalanceResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates EmployeeLeaveBalance - the allocation-only side of an employee's leave balance
 * (see EmployeeLeaveBalance's class javadoc for why "used"/"remaining" are never stored here).
 */
@Service
public class EmployeeLeaveBalanceService {

    private final EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public EmployeeLeaveBalanceService(EmployeeLeaveBalanceRepository employeeLeaveBalanceRepository,
                                        LeaveTypeRepository leaveTypeRepository,
                                        LeaveRequestRepository leaveRequestRepository) {
        this.employeeLeaveBalanceRepository = employeeLeaveBalanceRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    /**
     * Upsert-style lookup: if no balance row exists yet for this (employee, leaveType, year),
     * creates one seeded from LeaveType#defaultAllocationDays (carriedForwardDays=0) and returns
     * it; otherwise returns the existing row unchanged. Used by LeaveRequestService wherever a
     * concrete, persisted balance row is actually needed (as opposed to
     * #getBalanceSummary's read-only, non-persisting display convenience below).
     */
    @Transactional(noRollbackFor = NotFoundException.class)
    public EmployeeLeaveBalance getOrCreateForYear(UUID employeeId, UUID leaveTypeId, int year) {
        return employeeLeaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
                .orElseGet(() -> {
                    LeaveType leaveType = leaveTypeRepository.findById(leaveTypeId)
                            .orElseThrow(() -> new NotFoundException("Leave type not found: " + leaveTypeId));
                    EmployeeLeaveBalance balance = EmployeeLeaveBalance.builder()
                            .employeeId(employeeId)
                            .leaveTypeId(leaveTypeId)
                            .year(year)
                            .allocatedDays(leaveType.getDefaultAllocationDays())
                            .carriedForwardDays(BigDecimal.ZERO)
                            .build();
                    // saveAndFlush - see EmployeeService#create's comment re:
                    // @CreationTimestamp/@UpdateTimestamp only populating on an actual flush.
                    return employeeLeaveBalanceRepository.saveAndFlush(balance);
                });
    }

    /** ADMIN action: explicit upsert of both allocatedDays and carriedForwardDays for one (employee, leaveType, year). */
    @Transactional(noRollbackFor = NotFoundException.class)
    public EmployeeLeaveBalance setAllocation(UUID employeeId, UUID leaveTypeId, int year,
                                               BigDecimal allocatedDays, BigDecimal carriedForwardDays) {
        Optional<EmployeeLeaveBalance> existing =
                employeeLeaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year);
        EmployeeLeaveBalance balance;
        if (existing.isPresent()) {
            balance = existing.get();
        } else {
            // Validate leaveTypeId references a real in-tenant leave type before creating a
            // brand-new balance row for it (the "already exists" branch above needs no such
            // check - its leaveTypeId was already validated when the row was first created).
            leaveTypeRepository.findById(leaveTypeId)
                    .orElseThrow(() -> new NotFoundException("Leave type not found: " + leaveTypeId));
            balance = EmployeeLeaveBalance.builder()
                    .employeeId(employeeId)
                    .leaveTypeId(leaveTypeId)
                    .year(year)
                    .build();
        }
        balance.setAllocatedDays(allocatedDays);
        balance.setCarriedForwardDays(carriedForwardDays != null ? carriedForwardDays : BigDecimal.ZERO);
        // saveAndFlush - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        return employeeLeaveBalanceRepository.saveAndFlush(balance);
    }

    /**
     * Read-only convenience projection backing GET /leave-balances/*: for every active
     * LeaveType in the org, returns the allocated/carried-forward/used/remaining summary for
     * {@code year}. Deliberately does NOT persist a balance row just because it's being viewed -
     * if no EmployeeLeaveBalance row exists yet for a given leave type/year, this falls back to
     * that leave type's defaultAllocationDays and a zero carry-forward purely for display,
     * leaving actual persistence to an explicit admin #setAllocation or a lazy
     * #getOrCreateForYear call elsewhere (LeaveRequestService). Chosen over the alternative of
     * eagerly persisting a row on every view because a read endpoint silently writing rows as a
     * side effect of being called is more surprising than this simple "compute for display"
     * approach - simplest-correct wins here, consistent either way.
     */
    @Transactional(readOnly = true)
    public List<LeaveBalanceResponse> getBalanceSummary(UUID employeeId, int year) {
        List<LeaveType> activeLeaveTypes =
                leaveTypeRepository.findByActive(true, Sort.by(Sort.Direction.ASC, "sortOrder"));
        List<LeaveBalanceResponse> summaries = new ArrayList<>();
        for (LeaveType leaveType : activeLeaveTypes) {
            Optional<EmployeeLeaveBalance> existing = employeeLeaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveType.getId(), year);
            BigDecimal allocatedDays = existing.map(EmployeeLeaveBalance::getAllocatedDays)
                    .orElse(leaveType.getDefaultAllocationDays());
            BigDecimal carriedForwardDays = existing.map(EmployeeLeaveBalance::getCarriedForwardDays)
                    .orElse(BigDecimal.ZERO);
            BigDecimal usedDays = leaveRequestRepository.sumApprovedDays(employeeId, leaveType.getId(), year);
            BigDecimal remainingDays = allocatedDays.add(carriedForwardDays).subtract(usedDays);
            summaries.add(new LeaveBalanceResponse(leaveType.getId(), leaveType.getName(),
                    allocatedDays, carriedForwardDays, usedDays, remainingDays));
        }
        return summaries;
    }
}
