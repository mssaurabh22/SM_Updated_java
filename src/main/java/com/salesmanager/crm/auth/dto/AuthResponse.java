package com.salesmanager.crm.auth.dto;

import com.salesmanager.crm.employee.Role;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UUID employeeId,
        UUID orgId,
        Role role) {
}
