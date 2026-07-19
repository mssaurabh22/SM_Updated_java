package com.salesmanager.crm.leave;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leave.dto.LeaveTypeCreateRequest;
import com.salesmanager.crm.leave.dto.LeaveTypeResponse;
import com.salesmanager.crm.leave.dto.LeaveTypeUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-configurable Leave Type catalog (plan B.1a) - GET open to any authenticated user (an
 * employee submitting a leave request needs the active list too), mutations ADMIN-only, same
 * "generic reads, ADMIN-only mutations" split as MasterDataController. Every endpoint requires
 * EMPLOYEE_LEAVE_MANAGEMENT, per Part B of the entitlement plan.
 */
@RestController
@RequestMapping("/leave-types")
public class LeaveTypeController {

    private final LeaveTypeService leaveTypeService;

    public LeaveTypeController(LeaveTypeService leaveTypeService) {
        this.leaveTypeService = leaveTypeService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public List<LeaveTypeResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return leaveTypeService.list(includeInactive).stream()
                .map(LeaveTypeResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveTypeResponse create(@Valid @RequestBody LeaveTypeCreateRequest request) {
        return LeaveTypeResponse.from(leaveTypeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public LeaveTypeResponse update(@PathVariable UUID id, @Valid @RequestBody LeaveTypeUpdateRequest request) {
        return LeaveTypeResponse.from(leaveTypeService.update(id, request));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public LeaveTypeResponse deactivate(@PathVariable UUID id) {
        return LeaveTypeResponse.from(leaveTypeService.deactivate(id));
    }
}
