package com.salesmanager.crm.inventory;

/**
 * Thrown when a stock-out operation (manual adjustment or invoice-line deduction) would take a
 * Product's stock_quantity below zero. Mapped to 409 Conflict by GlobalExceptionHandler - same
 * rationale/status as leave.InsufficientLeaveBalanceException: a business-state conflict, not a
 * malformed payload.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
