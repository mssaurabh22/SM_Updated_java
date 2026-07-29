package com.salesmanager.crm.invoicing.dto;

import jakarta.validation.constraints.Size;

/** All fields optional/nullable - no format validation ("lightweight" v1, no GST-compliance
 * rigor). businessName is not editable here - it's Organization#name, set at registration.
 * The logo itself is uploaded separately via PUT /organizations/me/logo (multipart), not here. */
public record BillingProfileUpdateRequest(
        @Size(max = 500, message = "billingAddress must be at most 500 characters")
        String billingAddress,

        @Size(max = 20, message = "billingGstin must be at most 20 characters")
        String billingGstin,

        @Size(max = 50, message = "billingPhone must be at most 50 characters")
        String billingPhone,

        @Size(max = 1000, message = "invoiceHeaderText must be at most 1000 characters")
        String invoiceHeaderText,

        @Size(max = 1000, message = "invoiceFooterText must be at most 1000 characters")
        String invoiceFooterText) {
}
