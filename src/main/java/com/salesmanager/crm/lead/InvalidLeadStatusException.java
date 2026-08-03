package com.salesmanager.crm.lead;

/**
 * Thrown when a requested status change conflicts with the lead's current Interest Level - a
 * lead whose Interest Level isn't Hot is locked to INTERESTED (see LeadService#updateStatus).
 * Mapped to 409 Conflict by GlobalExceptionHandler, same rationale as
 * InvalidLeaveRequestStateException: the lead exists and the caller is authorized to act on it,
 * but its current state doesn't permit the requested transition.
 */
public class InvalidLeadStatusException extends RuntimeException {

    public InvalidLeadStatusException(String message) {
        super(message);
    }
}
