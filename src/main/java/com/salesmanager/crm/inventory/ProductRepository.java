package com.salesmanager.crm.inventory;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByActive(boolean active, Pageable pageable);

    /**
     * Pessimistic row lock for the stock-mutating paths (manual adjustment, invoice-line
     * deduction) - a plain unlocked findById() would let two concurrent requests both read the
     * same stock_quantity before either writes, losing one of the updates. See InvoiceService's
     * class javadoc for the lock-ordering discipline required when a single invoice locks more
     * than one product within the request-wide transaction TenantFilter already opens.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /** Exact-match SKU lookup for a scan-driven "find by barcode" flow (external keyboard-
     * wedge scanner or a manually-typed SKU + Enter) - see ProductService#getBySku. */
    Optional<Product> findBySkuIgnoreCase(String sku);
}
