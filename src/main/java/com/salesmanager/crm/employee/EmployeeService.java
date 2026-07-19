package com.salesmanager.crm.employee;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.dto.EmployeeCreateRequest;
import com.salesmanager.crm.employee.dto.EmployeeUpdateRequest;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Employee CRUD. Deliberately does NOT call TenantSessionManager.activateTenant
 * itself - by the time a normal authenticated admin request reaches this service, TenantFilter
 * has already activated the tenant context for the whole request, so TenantAware's
 * @PrePersist handles organizationId automatically (unlike AuthService's registration flow,
 * which runs before any tenant context exists).
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final MasterDataService masterDataService;
    private final PasswordEncoder passwordEncoder;

    public EmployeeService(EmployeeRepository employeeRepository,
                            MasterDataService masterDataService,
                            PasswordEncoder passwordEncoder) {
        this.employeeRepository = employeeRepository;
        this.masterDataService = masterDataService;
        this.passwordEncoder = passwordEncoder;
    }

    // noRollbackFor is essential, not cosmetic - see MasterDataService's identical comment:
    // TenantFilter wraps the whole request in one shared transaction, so an unmarked
    // RuntimeException here would poison that transaction even though
    // GlobalExceptionHandler translates it into a normal 4xx response, causing an
    // UnexpectedRollbackException to escape uncaught once the response is already committed.
    @Transactional(noRollbackFor = {InvalidReferenceException.class, NotFoundException.class})
    public Employee create(EmployeeCreateRequest request) {
        masterDataService.validateReference(request.designationId(), MasterType.DESIGNATION, "designationId");
        masterDataService.validateReference(request.stateId(), MasterType.STATE, "stateId");
        // Cross-checks cityId's parent against stateId when both are supplied on this
        // request - see MasterDataService#validateReference's 4-arg overload javadoc.
        masterDataService.validateReference(request.cityId(), MasterType.CITY, "cityId", request.stateId());
        Set<UUID> productIds = request.assignedProductIds() != null
                ? request.assignedProductIds() : Set.of();
        for (UUID productId : productIds) {
            masterDataService.validateReference(productId, MasterType.PRODUCT, "assignedProductIds");
        }
        // employeeId is null here (no id yet, hasn't been persisted) - see validateManager's
        // javadoc for why that makes an actual cycle impossible on create; the walk still runs
        // as a sanity guard against a pathologically long/broken existing chain.
        validateManager(null, request.managerId());

        Employee employee = Employee.builder()
                .fullName(request.fullName())
                .email(request.email())
                .phone(request.phone())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role())
                .active(true)
                .designationId(request.designationId())
                .cityId(request.cityId())
                .stateId(request.stateId())
                .assignedProductIds(new HashSet<>(productIds))
                .managerId(request.managerId())
                .build();
        // saveAndFlush (not save) is required: @CreationTimestamp/@UpdateTimestamp are only
        // populated in-memory when Hibernate actually flushes (builds the INSERT/UPDATE), not
        // at the moment save()/persist() is called. Since TenantFilter wraps the whole request
        // in one shared transaction that only commits (and would otherwise only flush) after
        // the response body has already been serialized, a plain save() here would return an
        // entity with createdAt/updatedAt still null to the client, even though the DB row
        // itself ends up correct once the outer transaction commits.
        return employeeRepository.saveAndFlush(employee);
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Employee update(UUID id, EmployeeUpdateRequest request) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + id));

        if (request.managerId() != null) {
            validateManager(id, request.managerId());
            employee.setManagerId(request.managerId());
        }
        if (request.designationId() != null) {
            masterDataService.validateReference(request.designationId(), MasterType.DESIGNATION, "designationId");
            employee.setDesignationId(request.designationId());
        }
        if (request.stateId() != null) {
            masterDataService.validateReference(request.stateId(), MasterType.STATE, "stateId");
            employee.setStateId(request.stateId());
        }
        if (request.cityId() != null) {
            // Cross-checks cityId's parent against stateId when both are supplied on this
            // request - see MasterDataService#validateReference's 4-arg overload javadoc.
            masterDataService.validateReference(request.cityId(), MasterType.CITY, "cityId", request.stateId());
            employee.setCityId(request.cityId());
        }
        if (request.assignedProductIds() != null) {
            for (UUID productId : request.assignedProductIds()) {
                masterDataService.validateReference(productId, MasterType.PRODUCT, "assignedProductIds");
            }
            employee.setAssignedProductIds(new HashSet<>(request.assignedProductIds()));
        }
        if (request.fullName() != null) {
            employee.setFullName(request.fullName());
        }
        if (request.phone() != null) {
            employee.setPhone(request.phone());
        }
        if (request.role() != null) {
            employee.setRole(request.role());
        }
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return employeeRepository.saveAndFlush(employee);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public Employee deactivate(UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + id));
        employee.setActive(false);
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return employeeRepository.saveAndFlush(employee);
    }

    /**
     * Validates a proposed managerId for {@code employeeId} (null on create - the new employee
     * has no id yet): rejects a self-reference, rejects a nonexistent managerId (NotFoundException -
     * relies on the same Hibernate filter/RLS backstop as every other id lookup in this
     * codebase to reject a cross-tenant id the same way), and walks the chain of managerId
     * pointers upward from the proposed manager to reject a cycle (the chain reaching back to
     * {@code employeeId}). Capped at depth 50 purely as a sanity guard against any bug rather
     * than looping forever - on create, employeeId is null so the "reaches back to employeeId"
     * check can never fire (there is no id yet for any existing employee to point at), but the
     * walk still runs and is still bounded, so a pathologically long or corrupted existing chain
     * is still caught rather than silently tolerated.
     */
    private void validateManager(UUID employeeId, UUID managerId) {
        if (managerId == null) {
            return;
        }
        if (managerId.equals(employeeId)) {
            throw new InvalidReferenceException("managerId", "An employee cannot be their own manager");
        }
        Employee manager = employeeRepository.findById(managerId)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + managerId));

        UUID currentId = manager.getManagerId();
        int depth = 0;
        while (currentId != null) {
            if (currentId.equals(employeeId)) {
                throw new InvalidReferenceException("managerId",
                        "managerId would create a management-hierarchy cycle");
            }
            if (++depth >= 50) {
                throw new InvalidReferenceException("managerId",
                        "managerId chain exceeds maximum depth (possible data integrity issue)");
            }
            currentId = employeeRepository.findById(currentId).map(Employee::getManagerId).orElse(null);
        }
    }
}
