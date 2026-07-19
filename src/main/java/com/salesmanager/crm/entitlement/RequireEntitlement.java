package com.salesmanager.crm.entitlement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level gate, enforced by {@link EntitlementAspect} - same shape as
 * {@code @PreAuthorize}, and checked alongside it, not instead of it. A request must still
 * pass normal role authorization; this additionally requires the current org (resolved from
 * {@code TenantContext}) to hold an active grant for {@link #value()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireEntitlement {

    FeatureEntitlement value();
}
