package com.salesmanager.crm.inventory;

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
 * Append-only stock ledger row - never updated or deleted after insert. Every row that changes
 * {@link Product#getStockQuantity()} must be inserted in the SAME transaction as that counter
 * update (see Product's class javadoc) so the two can never drift.
 */
@Entity
@Table(name = "stock_movements")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class StockMovement extends TenantAware {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** Positive = stock in, negative = stock out. */
    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockMovementReason reason;

    /** The Invoice id, when reason=INVOICE - null for a manual adjustment. */
    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
