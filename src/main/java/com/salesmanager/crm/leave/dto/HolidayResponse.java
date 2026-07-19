package com.salesmanager.crm.leave.dto;

import com.salesmanager.crm.leave.Holiday;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record HolidayResponse(
        UUID id,
        UUID organizationId,
        String name,
        LocalDate holidayDate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static HolidayResponse from(Holiday holiday) {
        return new HolidayResponse(
                holiday.getId(),
                holiday.getOrganizationId(),
                holiday.getName(),
                holiday.getHolidayDate(),
                holiday.getCreatedAt(),
                holiday.getUpdatedAt());
    }
}
