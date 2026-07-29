package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.InvoiceLineItem;
import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineItemResponse(
        UUID id,
        UUID productId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRatePercent,
        BigDecimal lineSubtotal,
        BigDecimal lineTaxAmount,
        int sortOrder) {

    public static InvoiceLineItemResponse from(InvoiceLineItem item) {
        return new InvoiceLineItemResponse(
                item.getId(),
                item.getProductId(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTaxRatePercent(),
                item.getLineSubtotal(),
                item.getLineTaxAmount(),
                item.getSortOrder());
    }
}
