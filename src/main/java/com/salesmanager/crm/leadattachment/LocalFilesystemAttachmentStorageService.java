package com.salesmanager.crm.leadattachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes attachment bytes to a directory on local disk - the deliberately simple v1 storage
 * backend (no S3 credentials/bucket needed to ship this). storageKey is the file's path
 * relative to {@code baseDir}, e.g. "{organizationId}/{leadId}/{uuid}-{filename}" - opaque to
 * every caller outside this class.
 */
@Service
public class LocalFilesystemAttachmentStorageService implements AttachmentStorageService {

    private final Path baseDir;

    public LocalFilesystemAttachmentStorageService(
            @Value("${attachment.local-storage-dir:./data/lead-attachments}") String baseDirPath) {
        this.baseDir = Path.of(baseDirPath).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID organizationId, UUID leadId, String originalFileName, InputStream content)
            throws IOException {
        String storageKey = organizationId + "/" + leadId + "/" + UUID.randomUUID() + "-" + sanitize(originalFileName);
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return storageKey;
    }

    @Override
    public byte[] load(String storageKey) throws IOException {
        return Files.readAllBytes(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    /** Guards against a storageKey (built from a client-supplied filename) resolving outside
     * baseDir - defense in depth on top of sanitize() stripping path separators already. */
    private Path resolve(String storageKey) throws IOException {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IOException("Resolved attachment path escapes the storage base directory");
        }
        return target;
    }

    /** Strips path separators/parent-dir segments from a client-supplied filename before it
     * becomes part of an on-disk path - the UUID prefix already guarantees uniqueness, this
     * guards purely against path traversal (e.g. "../../etc/passwd"). */
    private static String sanitize(String originalFileName) {
        String name = originalFileName != null ? originalFileName : "attachment";
        String baseName = Path.of(name).getFileName().toString();
        return baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
