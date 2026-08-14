package com.salesmanager.crm.customer.dto;

import com.salesmanager.crm.customer.Customer;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        UUID organizationId,
        String name,
        String contactPerson,
        String phone,
        String email,
        String address,
        UUID cityId,
        UUID stateId,
        UUID industryId,
        String gstin,
        String notes,
        boolean active,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getOrganizationId(),
                customer.getName(),
                customer.getContactPerson(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getAddress(),
                customer.getCityId(),
                customer.getStateId(),
                customer.getIndustryId(),
                customer.getGstin(),
                customer.getNotes(),
                customer.isActive(),
                customer.getCreatedBy(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}
