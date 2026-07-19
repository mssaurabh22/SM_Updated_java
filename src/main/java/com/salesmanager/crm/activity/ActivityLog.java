package com.salesmanager.crm.activity;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate `tenantFilter` WHERE clause is guaranteed to apply to this
 * entity's queries regardless of Hibernate version quirks around filter inheritance - same
 * pattern as Lead/Employee/MasterData/Visit/Notification.
 *
 * {@code ownerId}/{@code companyName} are deliberately denormalized SNAPSHOTS of the parent
 * Lead as of this event, not live joins - see V7__activity_log.sql's comment for the full
 * rationale (keeps every query, including the broad multi-lead feed, a simple indexed lookup
 * with no join to leads needed). {@code actorId} is null for system-generated entries (an
 * auto-scheduled stub Visit, a scheduler flipping a Visit/Lead to MISSED/LAPSED) and set to
 * the acting employee for everything a human did directly.
 */
@Entity
@Table(name = "activity_log")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ActivityLog extends TenantAware {

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityType type;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String description;
}
