package com.salesmanager.crm.invoicing;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

/** Thin wrapper around the openhtmltopdf render call - keeps InvoiceController simple and
 * gives this one operation (HTML -> PDF bytes) a single, testable seam. */
@Service
public class InvoicePdfService {

    private final InvoicePdfHtmlBuilder htmlBuilder;

    public InvoicePdfService(InvoicePdfHtmlBuilder htmlBuilder) {
        this.htmlBuilder = htmlBuilder;
    }

    public byte[] renderPdf(Invoice invoice, List<InvoiceLineItem> lineItems, BillingProfileResponse billingProfile,
                             String logoDataUri) {
        String html = htmlBuilder.build(invoice, lineItems, billingProfile, logoDataUri);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render invoice PDF for invoice " + invoice.getId(), e);
        }
    }
}
