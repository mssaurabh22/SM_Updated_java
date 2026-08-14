package com.salesmanager.crm.quotation.dto;

import com.salesmanager.crm.quotation.QuotationStatus;
import com.salesmanager.crm.visit.VisitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * customerName/contactPerson/etc. are always taken from this request as plain values (the
 * frontend pre-fills them from the picked Customer's CURRENT data at pick-time, editable from
 * there) - the server never re-derives/re-joins them from Customer itself, same discipline
 * invoicing.InvoiceCreateRequest already applies to its optional Lead link.
 *
 * <p>{@code status} must be DRAFT or SENT here - QuotationService rejects any other value on
 * create (APPROVED/REJECTED/CONVERTED only ever happen via a subsequent status-transition call).
 */
public record QuotationCreateRequest(
        UUID leadId,

        @NotNull(message = "customerId is required")
        UUID customerId,

        String customerContactPerson,
        String customerDesignation,
        String customerPhone,
        String customerEmail,
        String customerBillingAddress,
        String customerGstin,

        UUID industryId,
        @Size(max = 255) String industryOther,
        UUID cityId,
        @Size(max = 255) String cityOther,
        UUID stateId,
        @Size(max = 255) String stateOther,
        UUID interestLevelId,
        @Size(max = 255) String interestLevelOther,
        UUID businessTypeId,
        @Size(max = 255) String businessTypeOther,

        VisitType typeOfVisit,

        @NotNull(message = "quotationDate is required")
        LocalDate quotationDate,
        LocalDate validTillDate,
        @Size(max = 100) String referenceEnquiryNo,
        LocalDate expectedCloseDate,

        String quotationNotes,
        String termsAndConditions,
        String internalNote,

        LocalDate followUpDate,
        LocalTime followUpTime,
        UUID followUpByEmployeeId,
        @Size(max = 1000) String followUpNote,

        @NotEmpty(message = "lineItems must contain at least one item")
        @Valid
        List<QuotationLineItemRequest> lineItems,

        @NotNull(message = "status is required")
        QuotationStatus status) {
}
