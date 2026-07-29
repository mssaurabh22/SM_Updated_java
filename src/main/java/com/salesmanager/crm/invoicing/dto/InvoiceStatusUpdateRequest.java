package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.InvoiceStatus;
import jakarta.validation.constraints.NotNull;

public record InvoiceStatusUpdateRequest(
        @NotNull(message = "status is required")
        InvoiceStatus status) {
}
