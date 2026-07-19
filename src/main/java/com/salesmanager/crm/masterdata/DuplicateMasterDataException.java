package com.salesmanager.crm.masterdata;

/**
 * Thrown when creating a master-data entry whose (organization, type, code) would collide
 * with an existing one. Mapped to 409 Conflict by GlobalExceptionHandler - more correct
 * than a generic 400 for a uniqueness violation.
 */
public class DuplicateMasterDataException extends RuntimeException {

    public DuplicateMasterDataException(String message) {
        super(message);
    }
}
