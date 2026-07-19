package com.salesmanager.crm.notification;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate `tenantFilter` WHERE clause is guaranteed to apply to this
 * entity's queries regardless of Hibernate version quirks around filter inheritance - same
 * pattern as Lead/Employee/MasterData/Visit.
 *
 * {@code payload} is a plain hand-built JSON string (e.g.
 * {@code {"leadId":"...","companyName":"..."}}), stored in a {@code jsonb} column - no real
 * object-mapping infrastructure is built for this one Phase 3 use case.
 */
@Entity
@Table(name = "notifications")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Notification extends TenantAware {

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;
}
