package com.salesmanager.crm.employee;

/**
 * Thrown when creating or promoting an Employee to ADMIN would give an organization a second
 * Admin - each org has exactly one Admin (the "super admin"), enforced in
 * EmployeeService#create/update. Mapped to 409 Conflict by GlobalExceptionHandler, same
 * rationale as InvalidLeaveRequestStateException: not a malformed payload, a conflict with
 * existing org state.
 */
public class MultipleAdminNotAllowedException extends RuntimeException {

    public MultipleAdminNotAllowedException(String message) {
        super(message);
    }
}
