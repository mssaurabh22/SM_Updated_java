package com.salesmanager.crm.leadattachment;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Storage abstraction so LeadAttachmentService never depends on where attachment bytes
 * actually live. {@link LocalFilesystemAttachmentStorageService} is the only implementation
 * today (per the user's "local storage now, S3-ready later" decision); a future
 * S3AttachmentStorageService would be a drop-in second bean swapped in via config, with no
 * change needed to LeadAttachmentService, the entity, the controller, or the migration -
 * storageKey stays an opaque string either way.
 */
public interface AttachmentStorageService {

    /** Persists content under a fresh, opaque storage key and returns it. */
    String store(UUID organizationId, UUID leadId, String originalFileName, InputStream content) throws IOException;

    /** Loads previously-stored bytes back for download. */
    byte[] load(String storageKey) throws IOException;

    /** Best-effort delete - callers should not fail the surrounding request if this throws. */
    void delete(String storageKey) throws IOException;
}
