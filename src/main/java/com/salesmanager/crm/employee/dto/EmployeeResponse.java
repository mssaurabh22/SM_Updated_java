package com.salesmanager.crm.employee.dto;

import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.Role;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only projection of an Employee for API responses. Deliberately excludes
 * passwordHash - never serialize that field to clients.
 */
public record EmployeeResponse(
        UUID id,
        UUID organizationId,
        String fullName,
        String email,
        String phone,
        Role role,
        boolean active,
        UUID designationId,
        UUID cityId,
        UUID stateId,
        Set<UUID> assignedProductIds,
        UUID managerId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getOrganizationId(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getRole(),
                employee.isActive(),
                employee.getDesignationId(),
                employee.getCityId(),
                employee.getStateId(),
                employee.getAssignedProductIds(),
                employee.getManagerId(),
                employee.getCreatedAt(),
                employee.getUpdatedAt());
    }
}
