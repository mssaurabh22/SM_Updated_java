package com.salesmanager.crm.entitlement;

import lombok.Getter;

/**
 * Thrown by {@link EntitlementAspect} when the current org (from TenantContext) does not hold
 * an active grant for the annotated method's required {@link FeatureEntitlement}. Mapped by
 * GlobalExceptionHandler to 403 with {@code ErrorResponse.error} set to the literal string
 * {@code "FEATURE_NOT_ENTITLED"} - deliberately NOT the generic "Forbidden" reason phrase the
 * existing {@code AccessDeniedException} handler uses - so a frontend can distinguish "you
 * personally aren't allowed" from "your org hasn't licensed this" and render an upgrade
 * message instead of a bare permission error.
 */
@Getter
public class FeatureNotEntitledException extends RuntimeException {

    private final FeatureEntitlement entitlement;

    public FeatureNotEntitledException(FeatureEntitlement entitlement) {
        super("Organization is not entitled to feature: " + entitlement);
        this.entitlement = entitlement;
    }
}
