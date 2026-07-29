package com.salesmanager.crm.inventory;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.inventory.dto.ProductCreateRequest;
import com.salesmanager.crm.inventory.dto.ProductResponse;
import com.salesmanager.crm.inventory.dto.ProductUpdateRequest;
import com.salesmanager.crm.inventory.dto.StockAdjustmentRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET open to any entitled authenticated user (an employee creating an invoice needs the
 * catalog too), mutations ADMIN-only - same "generic reads, ADMIN-only mutations" split as
 * MasterDataController/LeaveTypeController. Every endpoint requires INVENTORY_MANAGEMENT.
 */
@RestController
@RequestMapping("/inventory/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public Page<ProductResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive,
                                       Pageable pageable) {
        return productService.list(includeInactive, pageable).map(ProductResponse::from);
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public ProductResponse getById(@PathVariable UUID id) {
        return ProductResponse.from(productService.getById(id));
    }

    /** Exact-match SKU lookup for a scan-driven flow (barcode scanner or manual SKU entry +
     * Enter) - see ProductService#getBySku. Two literal path segments ("by-sku" + the SKU
     * itself), so this never collides with the single-segment "/{id}" mapping above. */
    @GetMapping("/by-sku/{sku}")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public ProductResponse getBySku(@PathVariable String sku) {
        return ProductResponse.from(productService.getBySku(sku));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductCreateRequest request) {
        return ProductResponse.from(productService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductUpdateRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @PostMapping("/{id}/stock-adjustments")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVENTORY_MANAGEMENT)
    public ProductResponse adjustStock(@PathVariable UUID id, @Valid @RequestBody StockAdjustmentRequest request) {
        return ProductResponse.from(productService.adjustStock(id, request));
    }
}
