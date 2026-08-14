package com.salesmanager.crm.quotation.dto;

import com.salesmanager.crm.quotation.QuotationStatus;
import jakarta.validation.constraints.NotNull;

/** For the SENT -> APPROVED/REJECTED decision only - QuotationService rejects any other target
 * status through this endpoint (DRAFT/SENT are set via create/update, CONVERTED only via
 * QuotationController#convertToInvoice). */
public record QuotationStatusUpdateRequest(@NotNull(message = "status is required") QuotationStatus status) {
}
