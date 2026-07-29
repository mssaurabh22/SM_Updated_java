package com.salesmanager.crm.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * quantityChange is signed: positive = stock in, negative = stock out. Zero is rejected
 * server-side (a no-op adjustment is almost certainly a mistake, not an intentional action).
 */
public record StockAdjustmentRequest(
        @NotNull(message = "quantityChange is required")
        Integer quantityChange,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note) {
}
