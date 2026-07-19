package com.salesmanager.crm.leadimport;

/**
 * Thrown when an uploaded file for {@code POST /leads/import/preview} or {@code .../commit}
 * is either not a recognized type (not {@code .xlsx}/{@code .csv}) or claims to be one but
 * cannot actually be parsed (corrupt/truncated content). Mapped to a plain 400 Bad Request by
 * GlobalExceptionHandler - mirrors InvalidReferenceException's simple mapping, minus the
 * field-level detail (there is no single offending form field here, just "this file").
 */
public class UnsupportedImportFileException extends RuntimeException {

    public UnsupportedImportFileException(String message) {
        super(message);
    }
}
