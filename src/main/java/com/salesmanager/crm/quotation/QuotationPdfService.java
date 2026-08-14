package com.salesmanager.crm.quotation;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

/** Thin wrapper around the openhtmltopdf render call - same shape as invoicing.InvoicePdfService. */
@Service
public class QuotationPdfService {

    private final QuotationPdfHtmlBuilder htmlBuilder;

    public QuotationPdfService(QuotationPdfHtmlBuilder htmlBuilder) {
        this.htmlBuilder = htmlBuilder;
    }

    public byte[] renderPdf(Quotation quotation, List<QuotationLineItem> lineItems,
                             List<QuotationAttachment> attachments, BillingProfileResponse billingProfile,
                             String logoDataUri) {
        String html = htmlBuilder.build(quotation, lineItems, attachments, billingProfile, logoDataUri);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render quotation PDF for quotation " + quotation.getId(), e);
        }
    }
}
