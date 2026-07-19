package com.salesmanager.crm.entitlement;

import com.salesmanager.crm.security.TenantContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AOP enforcement for {@link RequireEntitlement}, same shape as {@code @PreAuthorize} - a
 * method interceptor around any bean method carrying the annotation. Reads the current org
 * the same way every other piece of this app that needs "the current org" does: TenantContext,
 * populated by TenantFilter for normal authenticated requests. Every endpoint this annotation
 * can appear on sits behind SecurityConfig's {@code anyRequest().authenticated()}, so a null
 * TenantContext here indicates a filter-chain misconfiguration, not a normal request-time
 * condition - hence the unchecked IllegalStateException rather than a mapped exception type
 * (same reasoning as CurrentUser's identical javadoc).
 */
@Aspect
@Component
public class EntitlementAspect {

    private final EntitlementService entitlementService;

    public EntitlementAspect(EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @Around("@annotation(requireEntitlement)")
    public Object enforce(ProceedingJoinPoint joinPoint, RequireEntitlement requireEntitlement) throws Throwable {
        UUID organizationId = TenantContext.getCurrentTenant();
        if (organizationId == null) {
            throw new IllegalStateException(
                    "RequireEntitlement checked with no active TenantContext - filter chain misconfigured");
        }
        if (!entitlementService.isEntitled(organizationId, requireEntitlement.value())) {
            throw new FeatureNotEntitledException(requireEntitlement.value());
        }
        return joinPoint.proceed();
    }
}
