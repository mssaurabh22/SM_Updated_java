package com.salesmanager.crm.inventory;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /**
     * Backs the counter/ledger reconciliation invariant asserted in StockAdjustmentIT - this sum
     * must always equal the corresponding Product#getStockQuantity(). Returns null (not 0) when
     * there are zero rows for a product - callers should treat null as "no movements yet".
     */
    @Query("SELECT SUM(m.quantityChange) FROM StockMovement m WHERE m.productId = :productId")
    Integer sumQuantityChangeByProductId(@Param("productId") UUID productId);
}
