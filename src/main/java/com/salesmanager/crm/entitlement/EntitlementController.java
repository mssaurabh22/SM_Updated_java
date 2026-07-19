package com.salesmanager.crm.entitlement;

import com.salesmanager.crm.entitlement.dto.EntitledResponse;
import com.salesmanager.crm.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Normal, tenant-scoped entitlement endpoints for authenticated users - unlike
 * {@link InternalEntitlementController}, these run through the ordinary JWT/TenantContext
 * flow, so "the current org" comes from the authenticated principal, not a path parameter.
 *
 * {@code /entitlement-check/employee-leave-management} is a deliberately minimal, disposable
 * proof that {@link RequireEntitlement} works end-to-end (Part A of the entitlement plan) -
 * there is no real feature to gate yet (Part B, Leave/Attendance, is a separate future phase).
 * It returns 200 only because {@link EntitlementAspect} let the request through; the
 * alternative (403 FEATURE_NOT_ENTITLED) is thrown by the aspect before this method body ever
 * runs. Delete or repurpose this endpoint once Part B ships a real protected endpoint to test
 * against instead.
 */
@RestController
public class EntitlementController {

    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public EntitlementController(EntitlementService entitlementService, CurrentUser currentUser) {
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
    }

    @GetMapping("/organizations/me/entitlements")
    public List<FeatureEntitlement> myEntitlements() {
        return List.copyOf(entitlementService.listActiveCodes(currentUser.get().getOrganizationId()));
    }

    @GetMapping("/entitlement-check/employee-leave-management")
    @RequireEntitlement(FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT)
    public EntitledResponse checkEmployeeLeaveManagement() {
        return new EntitledResponse(true);
    }
}
