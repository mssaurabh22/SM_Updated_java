package com.salesmanager.crm.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Deliberately has NO stockQuantity field - stock is only ever changed via the dedicated
 * stock-adjustment endpoint or invoice-line deduction, both of which insert a matching
 * StockMovement row in the same transaction. A plain field-edit path here would let the
 * maintained counter drift from the ledger.
 */
public record ProductUpdateRequest(
        @Size(max = 100, message = "sku must be at most 100 characters")
        String sku,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
        BigDecimal unitPrice,

        @DecimalMin(value = "0.0", message = "taxRatePercent must not be negative")
        BigDecimal taxRatePercent,

        @Size(max = 50, message = "unitOfMeasure must be at most 50 characters")
        String unitOfMeasure,

        @Size(max = 20, message = "hsnSacCode must be at most 20 characters")
        String hsnSacCode,

        Integer lowStockThreshold,

        @NotNull(message = "active is required")
        Boolean active) {
}
