package com.salesmanager.crm.common;

/**
 * Thrown when a requested resource does not exist OR belongs to another tenant.
 * Deliberately generic (no "forbidden" variant) so cross-tenant reads are indistinguishable
 * from truly-missing resources - this avoids leaking resource existence via a 403 vs 404 signal.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
