package com.salesmanager.crm.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.inventory.dto.ProductCreateRequest;
import com.salesmanager.crm.inventory.dto.ProductUpdateRequest;
import com.salesmanager.crm.inventory.dto.StockAdjustmentRequest;
import com.salesmanager.crm.notification.NotificationService;
import com.salesmanager.crm.notification.NotificationType;
import com.salesmanager.crm.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-configurable Product catalog + stock. {@code stockQuantity} is a maintained counter,
 * updated ONLY inside {@link #applyStockChange} (the single method both manual adjustment and
 * invoice-line deduction go through), always in the same transaction as inserting a
 * {@link StockMovement} row - see Product's class javadoc for why this never drifts.
 *
 * <p><b>Lock-ordering discipline</b>: {@link #applyStockChange} pessimistically locks the
 * Product row for the duration of the request-wide transaction TenantFilter already opens (see
 * ProductRepository#findByIdForUpdate). A caller that touches MORE THAN ONE product in a single
 * request (invoicing.InvoiceService) MUST acquire those locks in a fixed order (ascending
 * product id) across every code path that can lock more than one - otherwise two concurrent
 * requests referencing the same two products in opposite order deadlock. A single manual stock
 * adjustment here only ever locks one product, so this class alone can't deadlock; it becomes a
 * real risk once invoicing (which can reference several products in one request) calls into it.
 *
 * <p><b>Why {@link #lockAndCheckStock} exists as a separate step</b>: invoicing.InvoiceService
 * must validate ALL of an invoice's catalog lines (existence, active, sufficient stock) BEFORE
 * writing the Invoice/InvoiceLineItem rows - otherwise a mid-operation InsufficientStockException
 * would need those already-written rows to roll back, but this codebase's shared
 * request-wide-transaction architecture means {@code noRollbackFor} controls whether the WHOLE
 * transaction commits or rolls back, not just whether the HTTP response looks clean (see
 * InvoiceService's class javadoc for the full reasoning). Splitting into a read-only
 * lock+validate phase (safe to noRollbackFor unconditionally, since it never writes anything)
 * followed by a write phase that should no longer be able to fail is what makes both correctness
 * (no ghost invoices, no silent stock leaks) and a clean error response possible together.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final EmployeeRepository employeeRepository;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public ProductService(ProductRepository productRepository,
                           StockMovementRepository stockMovementRepository,
                           EmployeeRepository employeeRepository,
                           NotificationService notificationService,
                           CurrentUser currentUser,
                           ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.employeeRepository = employeeRepository;
        this.notificationService = notificationService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<Product> list(boolean includeInactive, Pageable pageable) {
        if (includeInactive) {
            return productRepository.findAll(pageable);
        }
        return productRepository.findByActive(true, pageable);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Product getById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }

    /** Exact-match, case-insensitive SKU lookup for a scan-driven "find by barcode" flow (a
     * keyboard-wedge external scanner needs no app-side SDK - it just "types" the barcode
     * followed by Enter into a focused input, which this endpoint then resolves in one request
     * instead of paging through the whole catalog client-side). Only an active product is
     * considered found, matching what's actually invoiceable (lockAndCheckStock's same rule). */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Product getBySku(String sku) {
        return productRepository.findBySkuIgnoreCase(sku)
                .filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException("No active product found for SKU: " + sku));
    }

    /**
     * saveAndFlush (not save) - see EmployeeService#create's comment re: @CreationTimestamp/
     * @UpdateTimestamp only populating in-memory on an actual Hibernate flush. If an initial
     * stockQuantity > 0 is supplied, a matching StockMovement row is inserted in the same
     * transaction so the counter/ledger reconciliation invariant holds from the very first row.
     */
    @Transactional
    public Product create(ProductCreateRequest request) {
        Product product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .unitPrice(request.unitPrice())
                .taxRatePercent(request.taxRatePercent() != null ? request.taxRatePercent() : java.math.BigDecimal.ZERO)
                .unitOfMeasure(request.unitOfMeasure())
                .hsnSacCode(request.hsnSacCode())
                .stockQuantity(request.stockQuantity())
                .lowStockThreshold(request.lowStockThreshold())
                .active(true)
                .build();
        Product saved = productRepository.saveAndFlush(product);

        if (request.stockQuantity() != null && request.stockQuantity() > 0) {
            StockMovement movement = StockMovement.builder()
                    .productId(saved.getId())
                    .quantityChange(request.stockQuantity())
                    .reason(StockMovementReason.MANUAL_ADJUSTMENT)
                    .note("Initial stock on product creation")
                    .createdBy(currentUser.get().getEmployeeId())
                    .build();
            stockMovementRepository.saveAndFlush(movement);
        }
        return saved;
    }

    /** Deliberately never touches stockQuantity - see ProductUpdateRequest's class javadoc. */
    @Transactional(noRollbackFor = NotFoundException.class)
    public Product update(UUID id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnitPrice(request.unitPrice());
        product.setTaxRatePercent(request.taxRatePercent() != null ? request.taxRatePercent() : java.math.BigDecimal.ZERO);
        product.setUnitOfMeasure(request.unitOfMeasure());
        product.setHsnSacCode(request.hsnSacCode());
        product.setLowStockThreshold(request.lowStockThreshold());
        product.setActive(request.active());
        return productRepository.saveAndFlush(product);
    }

    // noRollbackFor must list every exception thrown anywhere in this call chain, not just this
    // method's own body - applyStockChange() is called below via a plain `this.` call (same
    // bean), which bypasses the Spring AOP proxy entirely, so applyStockChange's OWN
    // @Transactional(noRollbackFor=...) has NO effect here; only THIS method's annotation is
    // seen by the proxy for the whole chain. (applyStockChange's own annotation still matters
    // when a DIFFERENT bean - invoicing.InvoiceService, Phase 2 - calls it directly.)
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidStockAdjustmentException.class,
            InsufficientStockException.class})
    public Product adjustStock(UUID id, StockAdjustmentRequest request) {
        if (request.quantityChange() == 0) {
            throw new InvalidStockAdjustmentException("quantityChange", "quantityChange must not be zero");
        }
        return applyStockChange(id, request.quantityChange(), StockMovementReason.MANUAL_ADJUSTMENT,
                null, request.note(), currentUser.get().getEmployeeId());
    }

    /**
     * Shared by manual adjustment (above) and invoice-line deduction (Phase 2, invoicing.
     * InvoiceService) - see this class's javadoc for the lock-ordering discipline a caller that
     * touches more than one product per request must follow.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InsufficientStockException.class})
    public Product applyStockChange(UUID productId, int quantityChange, StockMovementReason reason,
                                     UUID referenceId, String note, UUID actingEmployeeId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));

        int currentQuantity = product.getStockQuantity();
        int newQuantity = currentQuantity + quantityChange;
        if (newQuantity < 0) {
            throw new InsufficientStockException("Insufficient stock for product '" + product.getName()
                    + "': requested " + (-quantityChange) + ", available " + currentQuantity);
        }

        Integer threshold = product.getLowStockThreshold();
        boolean wasAboveThreshold = threshold == null || currentQuantity > threshold;
        boolean nowAtOrBelowThreshold = threshold != null && newQuantity <= threshold;

        product.setStockQuantity(newQuantity);
        Product saved = productRepository.saveAndFlush(product);

        StockMovement movement = StockMovement.builder()
                .productId(productId)
                .quantityChange(quantityChange)
                .reason(reason)
                .referenceId(referenceId)
                .note(note)
                .createdBy(actingEmployeeId)
                .build();
        stockMovementRepository.saveAndFlush(movement);

        // Fire only on the crossing itself, not on every subsequent stock-out while still low -
        // an org already notified about a low-stock product shouldn't get re-notified on every
        // invoice line item drawn against it until it's restocked back above threshold.
        if (wasAboveThreshold && nowAtOrBelowThreshold) {
            notifyLowStock(saved);
        }

        return saved;
    }

    /**
     * Read-only validation step for invoicing.InvoiceService's two-phase "validate everything
     * under a continuously-held lock BEFORE writing anything" flow (see that class's javadoc
     * for the full reasoning). Locks the product and confirms it's active and has at least
     * {@code requiredQuantity} in stock - throws but writes NOTHING, so it's always safe to
     * noRollbackFor regardless of how many products this is called for before one fails: since
     * none of these calls mutate anything, letting the shared request-wide transaction commit
     * normally afterward has nothing incorrect to persist. The caller must keep this same
     * transaction open through to the matching {@link #applyStockChange} call (no intervening
     * commit) so the lock is held continuously and the validated quantity can't change
     * underneath it.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InsufficientStockException.class})
    public Product lockAndCheckStock(UUID productId, int requiredQuantity) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        if (!product.isActive()) {
            throw new NotFoundException("Product not found: " + productId);
        }
        if (product.getStockQuantity() < requiredQuantity) {
            throw new InsufficientStockException("Insufficient stock for product '" + product.getName()
                    + "': requested " + requiredQuantity + ", available " + product.getStockQuantity());
        }
        return product;
    }

    private void notifyLowStock(Product product) {
        UUID organizationId = currentUser.get().getOrganizationId();
        String payload = buildLowStockPayload(product);
        for (Employee admin : employeeRepository.findByOrganizationIdAndRole(organizationId, Role.ADMIN)) {
            notificationService.create(admin.getId(), NotificationType.LOW_STOCK, payload);
        }
    }

    private String buildLowStockPayload(Product product) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "productId", product.getId().toString(),
                    "productName", product.getName(),
                    "stockQuantity", product.getStockQuantity()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LOW_STOCK notification payload", e);
        }
    }
}
