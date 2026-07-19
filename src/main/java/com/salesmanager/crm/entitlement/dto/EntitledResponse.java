package com.salesmanager.crm.entitlement.dto;

/** Response shape for the diagnostic /entitlement-check/* endpoint - see EntitlementController. */
public record EntitledResponse(boolean entitled) {
}
