package com.salesmanager.crm.masterdata.dto;

import com.salesmanager.crm.masterdata.MasterData;
import com.salesmanager.crm.masterdata.MasterType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record MasterDataResponse(
        UUID id,
        UUID organizationId,
        MasterType type,
        String code,
        String label,
        int sortOrder,
        boolean active,
        UUID parentId,
        String metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static MasterDataResponse from(MasterData masterData) {
        return new MasterDataResponse(
                masterData.getId(),
                masterData.getOrganizationId(),
                masterData.getType(),
                masterData.getCode(),
                masterData.getLabel(),
                masterData.getSortOrder(),
                masterData.isActive(),
                masterData.getParentId(),
                masterData.getMetadata(),
                masterData.getCreatedAt(),
                masterData.getUpdatedAt());
    }
}
