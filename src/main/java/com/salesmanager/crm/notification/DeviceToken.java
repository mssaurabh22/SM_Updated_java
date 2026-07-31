package com.salesmanager.crm.notification;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * An FCM registration token for one browser/device belonging to one employee - an employee can
 * have several (one per browser/device), all of which get a push for every notification they're
 * entitled to receive (see PushNotificationEventListener). {@code token} is globally unique (an
 * FCM token is only ever issued to one client at a time), so re-registering the same token from
 * the same or a different session just upserts {@code lastSeenAt} rather than duplicating - see
 * DeviceTokenService#register.
 */
@Entity
@Table(name = "device_tokens")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class DeviceToken extends TenantAware {

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false, length = 4096, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;
}
