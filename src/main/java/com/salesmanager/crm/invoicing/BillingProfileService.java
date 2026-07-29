package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import com.salesmanager.crm.invoicing.dto.BillingProfileUpdateRequest;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The seller header shown on a generated invoice PDF - mirrors theme.ThemeService's layering
 * (GET open to any authenticated user, PUT ADMIN-only via the controller), simpler here since
 * every field is a plain nullable column, not JSON needing parse/merge.
 *
 * <p>The logo is stored directly as bytea on Organization (see V14 migration comment) rather
 * than S3 - only PNG/JPEG, capped at {@link #MAX_LOGO_BYTES}, enforced here since neither is a
 * DB constraint.
 */
@Service
public class BillingProfileService {

    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_LOGO_CONTENT_TYPES = Set.of("image/png", "image/jpeg");

    private final OrganizationRepository organizationRepository;
    private final CurrentUser currentUser;

    public BillingProfileService(OrganizationRepository organizationRepository, CurrentUser currentUser) {
        this.organizationRepository = organizationRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public BillingProfileResponse getBillingProfile() {
        return BillingProfileResponse.from(loadCurrentOrganization());
    }

    @Transactional
    public BillingProfileResponse updateBillingProfile(BillingProfileUpdateRequest request) {
        Organization organization = loadCurrentOrganization();
        organization.setBillingAddress(request.billingAddress());
        organization.setBillingGstin(request.billingGstin());
        organization.setBillingPhone(request.billingPhone());
        organization.setInvoiceHeaderText(request.invoiceHeaderText());
        organization.setInvoiceFooterText(request.invoiceFooterText());
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    @Transactional(noRollbackFor = InvalidLogoException.class)
    public BillingProfileResponse uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidLogoException("file", "Logo file must not be empty");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new InvalidLogoException("file", "Logo file must be at most 2MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_LOGO_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidLogoException("file", "Logo must be a PNG or JPEG image");
        }

        Organization organization = loadCurrentOrganization();
        try {
            organization.setLogoImage(file.getBytes());
        } catch (java.io.IOException e) {
            throw new InvalidLogoException("file", "Failed to read uploaded logo file");
        }
        organization.setLogoContentType(contentType);
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    @Transactional
    public BillingProfileResponse deleteLogo() {
        Organization organization = loadCurrentOrganization();
        organization.setLogoImage(null);
        organization.setLogoContentType(null);
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    /** For the GET /organizations/me/logo preview endpoint - empty if no logo is set. */
    @Transactional(readOnly = true)
    public Optional<LogoBytes> getLogoBytes() {
        Organization organization = loadCurrentOrganization();
        if (organization.getLogoImage() == null) {
            return Optional.empty();
        }
        return Optional.of(new LogoBytes(organization.getLogoImage(), organization.getLogoContentType()));
    }

    /** For InvoicePdfHtmlBuilder - openhtmltopdf renders a data URI inline with no extra
     * network fetch, so the PDF stays self-contained and fast to render. */
    @Transactional(readOnly = true)
    public Optional<String> getLogoDataUri() {
        return getLogoBytes().map(logo ->
                "data:" + logo.contentType() + ";base64," + Base64.getEncoder().encodeToString(logo.bytes()));
    }

    private Organization loadCurrentOrganization() {
        UUID organizationId = currentUser.get().getOrganizationId();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    public record LogoBytes(byte[] bytes, String contentType) {
    }
}
