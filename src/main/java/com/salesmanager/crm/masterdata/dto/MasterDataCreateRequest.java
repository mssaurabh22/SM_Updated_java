package com.salesmanager.crm.masterdata.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record MasterDataCreateRequest(
        @NotBlank(message = "code is required")
        String code,

        @NotBlank(message = "label is required")
        String label,

        Integer sortOrder,

        UUID parentId,

        String metadata) {
}
