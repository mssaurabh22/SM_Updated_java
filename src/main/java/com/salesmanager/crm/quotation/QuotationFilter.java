package com.salesmanager.crm.quotation;

import java.time.LocalDate;
import java.util.UUID;

public record QuotationFilter(QuotationStatus status, UUID ownerId, UUID customerId, UUID cityId, UUID stateId,
                               LocalDate dateFrom, LocalDate dateTo, String search) {
}
