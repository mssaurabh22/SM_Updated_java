package com.salesmanager.crm.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;

/**
 * Spring Boot's {@code spring.jpa.open-in-view=true} normally binds the request-scoped
 * EntityManager via {@code OpenEntityManagerInViewInterceptor}, which only runs inside
 * {@code DispatcherServlet} - i.e. AFTER every servlet Filter, including
 * {@link com.salesmanager.crm.security.JwtAuthFilter} and
 * {@link com.salesmanager.crm.security.TenantFilter}.
 *
 * That means {@code TenantFilter} would otherwise call
 * {@code entityManager.unwrap(Session.class).enableFilter(...)} on a throwaway EntityManager
 * (Spring's shared-EM proxy opens-and-closes a temporary one when nothing is bound yet), which
 * gets discarded before the real repository query ever runs - so the Hibernate tenant filter
 * would silently never apply.
 *
 * Registering the classic {@link OpenEntityManagerInViewFilter} explicitly, ordered before the
 * Spring Security filter chain, binds the EntityManager to the thread early enough that
 * TenantFilter enables the filter on the SAME session the controller/repository later uses.
 * (Boot's own interceptor-based OSIV detects the already-bound EntityManager and just
 * participates in it, so there is no double-binding.)
 */
@Configuration
public class PersistenceWebConfig {

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> openEntityManagerInViewFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> registration =
                new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        registration.setOrder(Integer.MIN_VALUE + 10);
        return registration;
    }
}
