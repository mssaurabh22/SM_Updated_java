package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.InvoiceLineItem;
import com.salesmanager.crm.invoicing.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID organizationId,
        String invoiceNumber,
        UUID leadId,
        UUID ownerId,
        UUID createdBy,
        String customerName,
        String customerContactPerson,
        String customerPhone,
        String customerEmail,
        String customerAddress,
        String customerGstin,
        LocalDate invoiceDate,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        InvoiceStatus status,
        String notes,
        List<InvoiceLineItemResponse> lineItems,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static InvoiceResponse from(Invoice invoice, List<InvoiceLineItem> lineItems) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getOrganizationId(),
                invoice.getInvoiceNumber(),
                invoice.getLeadId(),
                invoice.getOwnerId(),
                invoice.getCreatedBy(),
                invoice.getCustomerName(),
                invoice.getCustomerContactPerson(),
                invoice.getCustomerPhone(),
                invoice.getCustomerEmail(),
                invoice.getCustomerAddress(),
                invoice.getCustomerGstin(),
                invoice.getInvoiceDate(),
                invoice.getSubtotal(),
                invoice.getTaxTotal(),
                invoice.getGrandTotal(),
                invoice.getStatus(),
                invoice.getNotes(),
                lineItems.stream().map(InvoiceLineItemResponse::from).toList(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt());
    }
}
