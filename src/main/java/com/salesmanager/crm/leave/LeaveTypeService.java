package com.salesmanager.crm.leave;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.leave.dto.LeaveTypeCreateRequest;
import com.salesmanager.crm.leave.dto.LeaveTypeUpdateRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only CRUD for the fully admin-configurable LeaveType catalog (plan B.1a) - mirrors
 * masterdata.MasterDataService's shape (active-only-by-default list, sortOrder ordering,
 * case-insensitive per-org unique code, soft-delete-only deactivate).
 */
@Service
public class LeaveTypeService {

    private static final Sort SORT_BY_SORT_ORDER = Sort.by(Sort.Direction.ASC, "sortOrder");

    private final LeaveTypeRepository leaveTypeRepository;

    public LeaveTypeService(LeaveTypeRepository leaveTypeRepository) {
        this.leaveTypeRepository = leaveTypeRepository;
    }

    /**
     * Active-only by default (what an employee submitting a request wants); includeInactive=true
     * returns everything (what the admin management screen wants). Always ordered by sortOrder.
     */
    @Transactional(readOnly = true)
    public List<LeaveType> list(boolean includeInactive) {
        if (includeInactive) {
            return leaveTypeRepository.findAll(SORT_BY_SORT_ORDER);
        }
        return leaveTypeRepository.findByActive(true, SORT_BY_SORT_ORDER);
    }

    // noRollbackFor is essential, not cosmetic - see MasterDataService#create's identical
    // comment: TenantFilter wraps the whole request in one shared transaction, so an unmarked
    // RuntimeException here would poison it even though GlobalExceptionHandler translates this
    // into a normal 409 response, causing a delayed UnexpectedRollbackException once the
    // response is already committed. Safe to exempt since this exception is always thrown
    // before any write in this method.
    @Transactional(noRollbackFor = DuplicateLeaveTypeException.class)
    public LeaveType create(LeaveTypeCreateRequest request) {
        if (leaveTypeRepository.existsByCodeIgnoreCase(request.code())) {
            throw new DuplicateLeaveTypeException(
                    "A leave type with code '" + request.code() + "' already exists");
        }
        LeaveType leaveType = LeaveType.builder()
                .name(request.name())
                .code(request.code())
                .defaultAllocationDays(request.defaultAllocationDays())
                .active(true)
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .build();
        // saveAndFlush (not save) - see EmployeeService#create's comment re:
        // @CreationTimestamp/@UpdateTimestamp only populating on an actual Hibernate flush.
        return leaveTypeRepository.saveAndFlush(leaveType);
    }

    /**
     * Policy-change propagation rule (plan B.1a): editing defaultAllocationDays here ONLY seeds
     * the default for a NEW EmployeeLeaveBalance row going forward (see
     * EmployeeLeaveBalanceService#getOrCreateForYear) - it deliberately does NOT retroactively
     * rewrite allocatedDays on any balance row that already exists for the current year, so an
     * employee's already-communicated entitlement never silently changes mid-year out from
     * under them. An admin who genuinely wants to adjust one employee's current-year balance
     * still can, directly, via EmployeeLeaveBalanceService#setAllocation - a deliberate, visible,
     * individual action, not an automatic side effect of this method.
     */
    @Transactional(noRollbackFor = NotFoundException.class)
    public LeaveType update(UUID id, LeaveTypeUpdateRequest request) {
        LeaveType leaveType = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Leave type not found: " + id));
        leaveType.setName(request.name());
        leaveType.setDefaultAllocationDays(request.defaultAllocationDays());
        leaveType.setSortOrder(request.sortOrder());
        leaveType.setActive(request.active());
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return leaveTypeRepository.saveAndFlush(leaveType);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public LeaveType deactivate(UUID id) {
        LeaveType leaveType = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Leave type not found: " + id));
        leaveType.setActive(false);
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return leaveTypeRepository.saveAndFlush(leaveType);
    }
}
