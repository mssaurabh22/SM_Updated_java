package com.salesmanager.crm.employeeactivity;

import com.salesmanager.crm.employeeactivity.dto.EmployeeActivityResponse;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - EmployeeActivityLogService enforces the EMPLOYEE-forced-to-own-id
 * visibility rule, same layering as activity.ActivityLogController. Part of the Leave module,
 * so every endpoint here requires EMPLOYEE_LEAVE_MANAGEMENT, same as every other endpoint in
 * this Part B slice.
 */
@RestController
public class EmployeeActivityLogController {

    private final EmployeeActivityLogService employeeActivityLogService;

    public EmployeeActivityLogController(EmployeeActivityLogService employeeActivityLogService) {
        this.employeeActivityLogService = employeeActivityLogService;
    }

    @GetMapping("/employee-activity")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public Page<EmployeeActivityResponse> list(@RequestParam(required = false) UUID employeeId,
                                                @RequestParam(required = false) EmployeeActivityType type,
                                                @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return employeeActivityLogService.list(employeeId, type, pageable).map(EmployeeActivityResponse::from);
    }
}
