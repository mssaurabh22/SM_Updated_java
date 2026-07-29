package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.inventory.InsufficientStockException;
import com.salesmanager.crm.inventory.Product;
import com.salesmanager.crm.inventory.ProductService;
import com.salesmanager.crm.inventory.StockMovementReason;
import com.salesmanager.crm.invoicing.dto.InvoiceCreateRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceLineItemRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceStatusUpdateRequest;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice is create-only in v1 - no edit/delete/void endpoint exists (see Invoice's class
 * javadoc), so there is deliberately no "reverse the stock deduction" compensating logic here.
 *
 * <p><b>Why {@link #create} validates ALL catalog lines BEFORE writing anything (critical)</b>:
 * TenantFilter wraps the WHOLE HTTP request in one shared transaction, and in this codebase's
 * established convention, a method's {@code noRollbackFor} doesn't just avoid an ugly HTTP
 * response - it determines whether that ENTIRE shared transaction commits (persisting
 * everything written in the request so far) or rolls back, since {@code noRollbackFor}
 * prevents the transaction from being marked rollback-only at all. That means excluding
 * InsufficientStockException/NotFoundException from rollback AFTER the Invoice/InvoiceLineItem
 * rows were already written would let a "failed" (409/404) invoice creation silently COMMIT a
 * ghost invoice anyway. The fix: do all locking/validation
 * (inventory.ProductService#lockAndCheckStock - read-only, throws nothing that could ever leave
 * bad data behind) for every catalog line FIRST, and only once every line has been confirmed
 * valid AND in-stock, write the Invoice/InvoiceLineItem rows and then actually decrement stock
 * (inventory.ProductService#applyStockChange, which by this point cannot legitimately fail -
 * the locks acquired during validation are held continuously through to this point, so nothing
 * else could have changed the checked quantities in between).
 *
 * <p><b>Lock-ordering discipline (critical)</b>: a single invoice can reference several catalog
 * products, each pessimistically locked for the request's full duration. Two concurrent
 * invoices referencing the same two products in opposite order is a textbook deadlock -
 * {@link #create} always locks/decrements DISTINCT products in ascending-id order (never the
 * order line items happen to appear in the request) to guarantee every transaction acquires
 * multi-product locks in the same relative order.
 *
 * <p><b>Fractional quantities</b>: InvoiceLineItem.quantity is a BigDecimal (ad-hoc lines may
 * be fractional, e.g. "1.5 hours consulting"), but Product.stockQuantity is a plain int - a
 * catalog line's quantity must therefore be a whole number (validated below); only an ad-hoc
 * line (no stock to touch) may be fractional.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final ProductService productService;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final CurrentUser currentUser;
    private final BillingProfileService billingProfileService;
    private final InvoicePdfService invoicePdfService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                           InvoiceLineItemRepository invoiceLineItemRepository,
                           InvoiceNumberService invoiceNumberService,
                           ProductService productService,
                           EmployeeHierarchyService employeeHierarchyService,
                           CurrentUser currentUser,
                           BillingProfileService billingProfileService,
                           InvoicePdfService invoicePdfService) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineItemRepository = invoiceLineItemRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.productService = productService;
        this.employeeHierarchyService = employeeHierarchyService;
        this.currentUser = currentUser;
        this.billingProfileService = billingProfileService;
        this.invoicePdfService = invoicePdfService;
    }

    // noRollbackFor must cover every exception Phase 1 can throw, not just whichever inner
    // method actually throws it - EVERY @Transactional method the exception propagates THROUGH
    // gets its own chance (via its own rollback rules) to mark the shared transaction
    // rollback-only, and create() is the last such boundary before the controller. Safe here
    // because all three are only ever thrown before Phase 2 writes anything - see this class's
    // javadoc.
    @Transactional(noRollbackFor = {InvalidInvoiceLineItemException.class, NotFoundException.class,
            InsufficientStockException.class})
    public Invoice create(InvoiceCreateRequest request) {
        UserPrincipal principal = currentUser.get();
        UUID actingEmployeeId = principal.getEmployeeId();

        // Phase 1 (read-only): structural validation first (no DB access), then aggregate
        // requested quantity per distinct catalog product, then lock + confirm each is active
        // and has enough stock IN ASCENDING-ID ORDER - one locked read per distinct product,
        // reused below for BOTH the stock check and the price/name snapshot, so there is no
        // separate unlocked read that could see a different price than what the lock confirmed.
        // Locks are held continuously (same transaction) through to Phase 2's deduction below,
        // so nothing can invalidate what was just checked here - see this class's javadoc.
        List<RawLine> rawLines = validateLineItemShapes(request.lineItems());
        Map<UUID, BigDecimal> quantityByProductId = aggregateQuantityByProduct(rawLines);
        Map<UUID, Product> lockedProducts = new HashMap<>();
        for (UUID productId : quantityByProductId.keySet()) {
            Product locked = productService.lockAndCheckStock(productId,
                    quantityByProductId.get(productId).intValueExact());
            lockedProducts.put(productId, locked);
        }
        List<ResolvedLine> resolvedLines = resolveLines(rawLines, lockedProducts);

        // Phase 2 (writes): everything from here on should not legitimately fail.
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (ResolvedLine line : resolvedLines) {
            subtotal = subtotal.add(line.lineSubtotal);
            taxTotal = taxTotal.add(line.lineTaxAmount);
        }
        subtotal = round(subtotal);
        taxTotal = round(taxTotal);
        BigDecimal grandTotal = round(subtotal.add(taxTotal));

        String invoiceNumber = invoiceNumberService.allocateNext(
                principal.getOrganizationId(), request.invoiceDate().getYear());

        Invoice invoice = Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .leadId(request.leadId())
                .ownerId(actingEmployeeId)
                .createdBy(actingEmployeeId)
                .customerName(request.customerName())
                .customerContactPerson(request.customerContactPerson())
                .customerPhone(request.customerPhone())
                .customerEmail(request.customerEmail())
                .customerAddress(request.customerAddress())
                .customerGstin(request.customerGstin())
                .invoiceDate(request.invoiceDate())
                .subtotal(subtotal)
                .taxTotal(taxTotal)
                .grandTotal(grandTotal)
                .status(InvoiceStatus.UNPAID)
                .notes(request.notes())
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        int sortOrder = 0;
        for (ResolvedLine line : resolvedLines) {
            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                    .invoiceId(savedInvoice.getId())
                    .productId(line.productId)
                    .description(line.description)
                    .quantity(line.quantity)
                    .unitPrice(line.unitPrice)
                    .taxRatePercent(line.taxRatePercent)
                    .lineSubtotal(line.lineSubtotal)
                    .lineTaxAmount(line.lineTaxAmount)
                    .sortOrder(sortOrder++)
                    .build();
            invoiceLineItemRepository.saveAndFlush(lineItem);
        }

        // Same ascending-id order as the Phase 1 validation loop above - the lock-ordering
        // discipline applies here too, even though these calls should no longer be able to fail.
        for (Map.Entry<UUID, BigDecimal> entry : quantityByProductId.entrySet()) {
            int quantity = entry.getValue().intValueExact();
            productService.applyStockChange(entry.getKey(), -quantity, StockMovementReason.INVOICE,
                    savedInvoice.getId(), "Invoice " + savedInvoice.getInvoiceNumber(), actingEmployeeId);
        }

        return savedInvoice;
    }

    /** Sums requested quantity per distinct product (referenced by more than one line means one
     * combined deduction) into a map ordered by ascending product id - see this class's javadoc
     * for why the order is not negotiable. */
    private Map<UUID, BigDecimal> aggregateQuantityByProduct(List<RawLine> rawLines) {
        Map<UUID, BigDecimal> quantityByProductId = new TreeMap<>(Comparator.naturalOrder());
        for (RawLine line : rawLines) {
            if (line.productId != null) {
                quantityByProductId.merge(line.productId, line.quantity, BigDecimal::add);
            }
        }
        return quantityByProductId;
    }

    /** A line item after structural validation only - a catalog line at this point carries just
     * its productId + quantity, NOT yet a price/name snapshot (that comes from the single
     * locked read in {@link #create}'s Phase 1, via {@link #resolveLines}). */
    private record RawLine(UUID productId, String description, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal taxRatePercent) {
    }

    /** One resolved, snapshot-ready line - either derived from the Phase 1 locked Product read
     * (catalog line) or taken as-is from the request (ad-hoc line). */
    private record ResolvedLine(UUID productId, String description, BigDecimal quantity,
                                 BigDecimal unitPrice, BigDecimal taxRatePercent,
                                 BigDecimal lineSubtotal, BigDecimal lineTaxAmount) {
    }

    /** Mutual exclusivity (exactly one of productId/description) and whole-number-quantity (for
     * a catalog line) validation - pure in-memory, no DB access, so it can never leave anything
     * to roll back regardless of how many line items are checked before one fails. */
    private List<RawLine> validateLineItemShapes(List<InvoiceLineItemRequest> requests) {
        List<RawLine> rawLines = new ArrayList<>();
        for (InvoiceLineItemRequest lineRequest : requests) {
            boolean hasProduct = lineRequest.productId() != null;
            boolean hasAdHocDescription = lineRequest.description() != null && !lineRequest.description().isBlank();
            if (hasProduct == hasAdHocDescription) {
                throw new InvalidInvoiceLineItemException("lineItems",
                        "Each line item must set exactly one of productId or description");
            }
            if (hasProduct && lineRequest.quantity().stripTrailingZeros().scale() > 0) {
                throw new InvalidInvoiceLineItemException("quantity",
                        "quantity must be a whole number for a catalog product line");
            }
            rawLines.add(new RawLine(lineRequest.productId(), lineRequest.description(), lineRequest.quantity(),
                    lineRequest.unitPrice(), lineRequest.taxRatePercent()));
        }
        return rawLines;
    }

    /** Turns each structurally-valid raw line into a fully snapshot-ready ResolvedLine - a
     * catalog line's name/price/tax come from the ALREADY-LOCKED Product read in Phase 1
     * (never a separate, potentially-stale unlocked read), an ad-hoc line uses its own request
     * values directly. */
    private List<ResolvedLine> resolveLines(List<RawLine> rawLines, Map<UUID, Product> lockedProducts) {
        List<ResolvedLine> resolved = new ArrayList<>();
        for (RawLine line : rawLines) {
            if (line.productId != null) {
                Product product = lockedProducts.get(line.productId);
                BigDecimal lineSubtotal = round(product.getUnitPrice().multiply(line.quantity));
                BigDecimal lineTaxAmount = round(lineSubtotal
                        .multiply(product.getTaxRatePercent())
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
                resolved.add(new ResolvedLine(product.getId(), product.getName(), line.quantity,
                        product.getUnitPrice(), product.getTaxRatePercent(), lineSubtotal, lineTaxAmount));
            } else {
                BigDecimal unitPrice = line.unitPrice != null ? line.unitPrice : BigDecimal.ZERO;
                BigDecimal taxRatePercent = line.taxRatePercent != null ? line.taxRatePercent : BigDecimal.ZERO;
                BigDecimal lineSubtotal = round(unitPrice.multiply(line.quantity));
                BigDecimal lineTaxAmount = round(lineSubtotal
                        .multiply(taxRatePercent)
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
                resolved.add(new ResolvedLine(null, line.description, line.quantity,
                        unitPrice, taxRatePercent, lineSubtotal, lineTaxAmount));
            }
        }
        return resolved;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> list(InvoiceFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<Invoice> spec = Specification.where(InvoiceSpecifications.hasStatus(filter.status()));

        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(InvoiceSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(InvoiceSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(InvoiceSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            spec = spec.and(InvoiceSpecifications.hasOwner(filter.ownerId()));
        }

        return invoiceRepository.findAll(spec, pageable);
    }

    /** ADMIN can fetch any invoice in their org; EMPLOYEE gets a NotFoundException (never a
     * 403) for a colleague's invoice unless TEAM_VISIBILITY is entitled and its owner is in
     * their subordinate chain - same information-hiding principle as lead.LeadService#getById. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Invoice getById(UUID id) {
        return loadForCurrentUser(id, true);
    }

    @Transactional(readOnly = true)
    public List<InvoiceLineItem> getLineItems(UUID invoiceId) {
        return invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId);
    }

    /** Same visibility scoping as {@link #getById} (via loadForCurrentUser) - a colleague's
     * invoice can't be downloaded any more than it can be viewed. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public byte[] renderInvoicePdf(UUID id) {
        Invoice invoice = loadForCurrentUser(id, true);
        List<InvoiceLineItem> lineItems = getLineItems(id);
        String logoDataUri = billingProfileService.getLogoDataUri().orElse(null);
        return invoicePdfService.renderPdf(invoice, lineItems, billingProfileService.getBillingProfile(), logoDataUri);
    }

    /** TEAM_VISIBILITY is a READ-only grant here too, same rule as Lead - a manager can see but
     * not mark a subordinate's invoice paid through this feature alone. */
    @Transactional(noRollbackFor = NotFoundException.class)
    public Invoice updateStatus(UUID id, InvoiceStatusUpdateRequest request) {
        Invoice invoice = loadForCurrentUser(id, false);
        invoice.setStatus(request.status());
        return invoiceRepository.saveAndFlush(invoice);
    }

    private Invoice loadForCurrentUser(UUID id, boolean allowTeamVisibility) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !invoice.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(invoice.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Invoice not found: " + id);
            }
        }
        return invoice;
    }
}
