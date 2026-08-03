package com.salesmanager.crm.leadattachment;

/**
 * Thrown when an uploaded attachment is empty, exceeds the size limit, or has an unsupported
 * content type. Mapped to a plain 400 Bad Request by GlobalExceptionHandler - mirrors
 * UnsupportedImportFileException's simple mapping.
 */
public class InvalidAttachmentException extends RuntimeException {

    public InvalidAttachmentException(String message) {
        super(message);
    }
}
