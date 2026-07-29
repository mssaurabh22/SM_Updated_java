package com.salesmanager.crm.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProductCreateRequest(
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

        @NotNull(message = "stockQuantity is required")
        @Min(value = 0, message = "stockQuantity must not be negative")
        Integer stockQuantity,

        @Min(value = 0, message = "lowStockThreshold must not be negative")
        Integer lowStockThreshold) {
}
