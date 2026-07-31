package com.salesmanager.crm.notification.dto;

import com.salesmanager.crm.notification.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DeviceTokenRequest(
        @NotBlank(message = "token is required")
        String token,

        @NotNull(message = "platform is required")
        DevicePlatform platform) {
}
