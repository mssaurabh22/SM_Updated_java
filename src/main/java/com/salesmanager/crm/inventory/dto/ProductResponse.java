package com.salesmanager.crm.inventory.dto;

import com.salesmanager.crm.inventory.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID organizationId,
        String sku,
        String name,
        String description,
        BigDecimal unitPrice,
        BigDecimal taxRatePercent,
        String unitOfMeasure,
        int stockQuantity,
        Integer lowStockThreshold,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getOrganizationId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getUnitPrice(),
                product.getTaxRatePercent(),
                product.getUnitOfMeasure(),
                product.getStockQuantity(),
                product.getLowStockThreshold(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
