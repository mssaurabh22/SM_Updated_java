package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.invoicing.dto.InvoiceCreateRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceResponse;
import com.salesmanager.crm.invoicing.dto.InvoiceStatusUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * No ADMIN-only restriction here - any authenticated employee closes and invoices their own
 * deals, same as Lead creation. Visibility (list/getById) is owner-scoped, expanded by
 * TEAM_VISIBILITY exactly like lead.LeadController. Every endpoint requires
 * INVENTORY_MANAGEMENT.
 */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public Page<InvoiceResponse> list(@RequestParam(required = false) InvoiceStatus status,
                                       @RequestParam(required = false) UUID ownerId,
                                       Pageable pageable) {
        InvoiceFilter filter = new InvoiceFilter(status, ownerId);
        return invoiceService.list(filter, pageable)
                .map(invoice -> InvoiceResponse.from(invoice, invoiceService.getLineItems(invoice.getId())));
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public InvoiceResponse getById(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getById(id);
        return InvoiceResponse.from(invoice, invoiceService.getLineItems(id));
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody InvoiceCreateRequest request) {
        Invoice invoice = invoiceService.create(request);
        return InvoiceResponse.from(invoice, invoiceService.getLineItems(invoice.getId()));
    }

    @PatchMapping("/{id}/status")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public InvoiceResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody InvoiceStatusUpdateRequest request) {
        Invoice invoice = invoiceService.updateStatus(id, request);
        return InvoiceResponse.from(invoice, invoiceService.getLineItems(id));
    }

    /** First binary-download endpoint in this codebase - plain ResponseEntity<byte[]>, nothing
     * exotic needed. */
    @GetMapping("/{id}/pdf")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getById(id);
        byte[] pdfBytes = invoiceService.renderInvoicePdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .body(pdfBytes);
    }
}
