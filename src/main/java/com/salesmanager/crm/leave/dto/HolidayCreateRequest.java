package com.salesmanager.crm.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record HolidayCreateRequest(
        @NotBlank(message = "name is required")
        String name,

        @NotNull(message = "holidayDate is required")
        LocalDate holidayDate) {
}
