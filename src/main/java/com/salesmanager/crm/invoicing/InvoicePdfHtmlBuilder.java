package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the fixed HTML+CSS layout rendered to PDF by {@link InvoicePdfService}. Centralized
 * here (not scattered string concatenation in the service) specifically so every
 * user-supplied field is escaped in exactly one place - every value below that could contain
 * arbitrary customer/rep-typed text (customer name/address/notes, ad-hoc line descriptions)
 * goes through {@link #esc} before landing in the HTML, or a stray {@code <}/{@code &}/quote
 * would either break openhtmltopdf's strict XHTML parser or be misinterpreted as markup.
 */
@Component
class InvoicePdfHtmlBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** logoDataUri is a full {@code data:image/...;base64,...} string (or null) - built by
     * BillingProfileService#getLogoDataUri so openhtmltopdf renders it inline with no extra
     * network fetch, keeping PDF rendering self-contained and fast. */
    String build(Invoice invoice, List<InvoiceLineItem> lineItems, BillingProfileResponse billingProfile,
                  String logoDataUri) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append(css())
                .append("</style></head><body>");

        html.append("<div class=\"header\">")
                .append("<div class=\"seller\">");
        if (isNotBlank(logoDataUri)) {
            html.append("<img class=\"logo\" src=\"").append(logoDataUri).append("\"/>");
        }
        html.append("<div class=\"business-name\">").append(esc(billingProfile.businessName())).append("</div>");
        if (isNotBlank(billingProfile.billingAddress())) {
            html.append("<div>").append(esc(billingProfile.billingAddress())).append("</div>");
        }
        if (isNotBlank(billingProfile.billingGstin())) {
            html.append("<div>GSTIN: ").append(esc(billingProfile.billingGstin())).append("</div>");
        }
        if (isNotBlank(billingProfile.billingPhone())) {
            html.append("<div>Phone: ").append(esc(billingProfile.billingPhone())).append("</div>");
        }
        if (isNotBlank(billingProfile.invoiceHeaderText())) {
            html.append("<div class=\"header-text\">").append(esc(billingProfile.invoiceHeaderText())).append("</div>");
        }
        html.append("</div>")
                .append("<div class=\"invoice-meta\">")
                .append("<div class=\"invoice-title\">TAX INVOICE</div>")
                .append("<div>Invoice No.: ").append(esc(invoice.getInvoiceNumber())).append("</div>")
                .append("<div>Invoice Date: ").append(esc(invoice.getInvoiceDate().format(DATE_FORMAT))).append("</div>");
        if (invoice.getDueDate() != null) {
            html.append("<div>Due Date: ").append(esc(invoice.getDueDate().format(DATE_FORMAT))).append("</div>");
        }
        if (isNotBlank(invoice.getPlaceOfSupply())) {
            html.append("<div>Place of Supply: ").append(esc(invoice.getPlaceOfSupply())).append("</div>");
        }
        html.append("<div>Reverse Charge: ").append(invoice.isReverseCharge() ? "Yes" : "No").append("</div>")
                .append("</div>")
                .append("</div>");

        html.append("<div class=\"party-row\">");
        html.append("<div class=\"bill-to\"><div class=\"section-label\">Bill To</div>")
                .append("<div>").append(esc(invoice.getCustomerName())).append("</div>");
        if (isNotBlank(invoice.getCustomerContactPerson())) {
            html.append("<div>").append(esc(invoice.getCustomerContactPerson())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerPhone())) {
            html.append("<div>").append(esc(invoice.getCustomerPhone())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerEmail())) {
            html.append("<div>").append(esc(invoice.getCustomerEmail())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerAddress())) {
            html.append("<div>").append(esc(invoice.getCustomerAddress())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerGstin())) {
            html.append("<div>GSTIN: ").append(esc(invoice.getCustomerGstin())).append("</div>");
        }
        html.append("</div>");

        String shipToName = isNotBlank(invoice.getShipToName()) ? invoice.getShipToName() : invoice.getCustomerName();
        String shipToAddress = isNotBlank(invoice.getShipToAddress())
                ? invoice.getShipToAddress() : invoice.getCustomerAddress();
        html.append("<div class=\"ship-to\"><div class=\"section-label\">Ship To</div>")
                .append("<div>").append(esc(shipToName)).append("</div>");
        if (isNotBlank(shipToAddress)) {
            html.append("<div>").append(esc(shipToAddress)).append("</div>");
        }
        if (isNotBlank(invoice.getShipToGstin())) {
            html.append("<div>GSTIN: ").append(esc(invoice.getShipToGstin())).append("</div>");
        }
        html.append("</div>");
        html.append("</div>");

        html.append("<table class=\"lines\"><thead><tr>")
                .append("<th>Description</th><th>HSN/SAC</th><th class=\"num\">Qty</th>")
                .append("<th class=\"num\">Unit Price</th><th class=\"num\">Discount</th>")
                .append("<th class=\"num\">Taxable Value</th><th class=\"num\">CGST</th>")
                .append("<th class=\"num\">SGST</th><th class=\"num\">Total</th>")
                .append("</tr></thead><tbody>");
        for (InvoiceLineItem line : lineItems) {
            java.math.BigDecimal taxableValue = line.getLineSubtotal().subtract(line.getLineDiscountAmount());
            java.math.BigDecimal lineTotal = taxableValue.add(line.getLineCgstAmount()).add(line.getLineSgstAmount());
            html.append("<tr>")
                    .append("<td>").append(esc(line.getDescription())).append("</td>")
                    .append("<td>").append(esc(line.getHsnSac())).append("</td>")
                    .append("<td class=\"num\">").append(line.getQuantity()).append("</td>")
                    .append("<td class=\"num\">").append(line.getUnitPrice()).append("</td>")
                    .append("<td class=\"num\">").append(line.getLineDiscountAmount()).append("</td>")
                    .append("<td class=\"num\">").append(taxableValue).append("</td>")
                    .append("<td class=\"num\">").append(line.getLineCgstAmount()).append("</td>")
                    .append("<td class=\"num\">").append(line.getLineSgstAmount()).append("</td>")
                    .append("<td class=\"num\">").append(lineTotal).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");

        java.math.BigDecimal totalDiscount = lineItems.stream()
                .map(InvoiceLineItem::getLineDiscountAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        html.append("<div class=\"totals\">")
                .append("<div>Subtotal: ").append(invoice.getSubtotal()).append("</div>")
                .append("<div>Total Discount: ").append(totalDiscount).append("</div>")
                .append("<div>Tax: ").append(invoice.getTaxTotal()).append("</div>")
                .append("<div class=\"grand-total\">Grand Total: ").append(invoice.getGrandTotal()).append("</div>")
                .append("</div>");

        if (isNotBlank(invoice.getNotes())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Notes</div><div>")
                    .append(esc(invoice.getNotes())).append("</div></div>");
        }

        boolean hasBankDetails = isNotBlank(billingProfile.bankName()) || isNotBlank(billingProfile.bankAccountNumber())
                || isNotBlank(billingProfile.bankIfsc()) || isNotBlank(billingProfile.upiId());
        if (hasBankDetails) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Payment Details</div>");
            if (isNotBlank(billingProfile.bankName())) {
                html.append("<div>Bank Name: ").append(esc(billingProfile.bankName())).append("</div>");
            }
            if (isNotBlank(billingProfile.bankAccountNumber())) {
                html.append("<div>A/C No.: ").append(esc(billingProfile.bankAccountNumber())).append("</div>");
            }
            if (isNotBlank(billingProfile.bankIfsc())) {
                html.append("<div>IFSC Code: ").append(esc(billingProfile.bankIfsc())).append("</div>");
            }
            if (isNotBlank(billingProfile.bankBranch())) {
                html.append("<div>Branch: ").append(esc(billingProfile.bankBranch())).append("</div>");
            }
            if (isNotBlank(billingProfile.upiId())) {
                html.append("<div>UPI ID: ").append(esc(billingProfile.upiId())).append("</div>");
            }
            html.append("</div>");
        }

        html.append("<div class=\"signature\">")
                .append("<div>For ").append(esc(billingProfile.businessName())).append("</div>")
                .append("<div class=\"signature-space\"></div>")
                .append("<div>Authorized Signatory</div>")
                .append("</div>");

        if (isNotBlank(billingProfile.invoiceFooterText())) {
            html.append("<div class=\"footer-text\">").append(esc(billingProfile.invoiceFooterText())).append("</div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String css() {
        return "body { font-family: Helvetica, Arial, sans-serif; font-size: 11pt; color: #222; } "
                + ".header { display: flex; justify-content: space-between; margin-bottom: 24px; } "
                + ".logo { max-height: 60px; max-width: 220px; display: block; margin-bottom: 8px; } "
                + ".business-name { font-size: 14pt; font-weight: bold; } "
                + ".header-text { margin-top: 6px; font-size: 9pt; color: #555; } "
                + ".invoice-meta { text-align: right; } "
                + ".invoice-title { font-size: 18pt; font-weight: bold; letter-spacing: 2px; } "
                + ".section-label { font-weight: bold; text-transform: uppercase; font-size: 9pt; "
                + "color: #666; margin-bottom: 4px; } "
                + ".party-row { display: flex; justify-content: space-between; margin-bottom: 20px; } "
                + ".bill-to, .ship-to { width: 48%; } "
                + "table.lines { width: 100%; border-collapse: collapse; margin-bottom: 16px; } "
                + "table.lines th, table.lines td { border-bottom: 1px solid #ddd; padding: 6px 8px; "
                + "text-align: left; } "
                + "table.lines th.num, table.lines td.num { text-align: right; } "
                + ".totals { text-align: right; } "
                + ".totals .grand-total { font-size: 13pt; font-weight: bold; margin-top: 6px; } "
                + ".notes { margin-top: 20px; } "
                + ".signature { margin-top: 40px; text-align: right; } "
                + ".signature-space { height: 40px; } "
                + ".footer-text { margin-top: 24px; padding-top: 8px; border-top: 1px solid #ddd; "
                + "font-size: 9pt; color: #555; white-space: pre-wrap; }";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
