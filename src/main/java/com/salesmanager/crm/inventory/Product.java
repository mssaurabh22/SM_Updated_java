package com.salesmanager.crm.inventory;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * A real, priced/stocked catalog entry - deliberately a brand-new entity, not an extension of
 * the generic {@code master_data} table (whose existing PRODUCT type has no price/stock and
 * stays exactly as-is, still used by Lead/Visit's own unrelated {@code productIds}).
 *
 * {@code stockQuantity} is a MAINTAINED counter, updated only inside the same transaction as
 * inserting a {@link StockMovement} row (manual adjustment or invoice deduction) - never edited
 * any other way. See {@code V11__inventory.sql}'s comment for why a maintained counter over an
 * append-only ledger is the right call here (unlike EmployeeLeaveBalance, which must always
 * live-compute because its underlying history is mutable).
 *
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate {@code tenantFilter} WHERE clause is guaranteed to apply,
 * same pattern as every other entity in this codebase.
 */
@Entity
@Table(name = "products")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Product extends TenantAware {

    @Column(length = 100)
    private String sku;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "unit_price", nullable = false)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "tax_rate_percent", nullable = false)
    @Builder.Default
    private BigDecimal taxRatePercent = BigDecimal.ZERO;

    @Column(name = "unit_of_measure", length = 50)
    private String unitOfMeasure;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private int stockQuantity = 0;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
