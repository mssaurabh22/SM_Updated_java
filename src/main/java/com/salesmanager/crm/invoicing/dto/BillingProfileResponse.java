package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.tenant.Organization;

/** The seller header shown on a generated invoice PDF - businessName is Organization#name
 * itself (not editable via this endpoint, set at org registration), the rest are the
 * optional billing_* columns. hasLogo tells the frontend whether to fetch
 * GET /organizations/me/logo for a preview - the logo bytes themselves are never embedded
 * here, so a routine billing-profile fetch stays small. */
public record BillingProfileResponse(
        String businessName,
        String billingAddress,
        String billingGstin,
        String billingPhone,
        boolean hasLogo,
        String invoiceHeaderText,
        String invoiceFooterText) {

    public static BillingProfileResponse from(Organization organization) {
        return new BillingProfileResponse(
                organization.getName(),
                organization.getBillingAddress(),
                organization.getBillingGstin(),
                organization.getBillingPhone(),
                organization.getLogoImage() != null,
                organization.getInvoiceHeaderText(),
                organization.getInvoiceFooterText());
    }
}
