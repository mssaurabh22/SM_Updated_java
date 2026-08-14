package com.salesmanager.crm.quotation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Exactly one of {@code productId} (catalog line - description/unitPrice/taxRatePercent/hsnSac
 * are derived from the Product instead) or {@code description}+{@code unitPrice} (ad-hoc line)
 * must be set - enforced in QuotationService, same shape as invoicing's line-item contract.
 */
public record QuotationLineItemRequest(
        UUID productId,

        @Size(max = 20, message = "hsnSac must be at most 20 characters")
        String hsnSac,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "quantity is required")
        @DecimalMin(value = "0.01", message = "quantity must be greater than zero")
        BigDecimal quantity,

        @Size(max = 50, message = "unit must be at most 50 characters")
        String unit,

        @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
        BigDecimal unitPrice,

        @DecimalMin(value = "0.0", message = "discountPercent must not be negative")
        @DecimalMax(value = "100.0", message = "discountPercent must not exceed 100")
        BigDecimal discountPercent,

        @DecimalMin(value = "0.0", message = "taxRatePercent must not be negative")
        BigDecimal taxRatePercent) {
}
