package com.salesmanager.crm.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.security.TenantSessionManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual stock adjustment (POST /inventory/products/{id}/stock-adjustments): the counter/ledger
 * reconciliation invariant (SUM(stock_movements.quantity_change) always equals
 * products.stock_quantity), negative-stock rejection (409, DB CHECK backstop never reached
 * because the app-layer check fires first), zero-adjustment rejection (400), and the low-stock
 * notification firing exactly once per threshold-crossing (see ProductService#applyStockChange).
 */
class StockAdjustmentIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private TenantSessionManager tenantSessionManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void adjustStock_increasesAndDecreases_ledgerAlwaysReconcilesToCounter() {
        AuthResponse admin = registerOrganization("Stock Adjustment Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Extended Warranty", "500.00", 10);

        adjust(admin.accessToken(), productId, 15, "Received new batch");
        adjust(admin.accessToken(), productId, -3, "Damaged unit written off");
        JsonNode afterThree = parse(get("/inventory/products/" + productId, admin.accessToken()).getBody());
        assertThat(afterThree.get("stockQuantity").asInt()).isEqualTo(22); // 10 + 15 - 3

        adjust(admin.accessToken(), productId, -22, "Final drawdown to zero");
        JsonNode atZero = parse(get("/inventory/products/" + productId, admin.accessToken()).getBody());
        assertThat(atZero.get("stockQuantity").asInt()).isEqualTo(0);

        Integer ledgerSum = sumQuantityChange(admin.orgId(), productId);
        assertThat(ledgerSum).isEqualTo(0); // initial 10 + 15 - 3 - 22 = 0, matching the counter
    }

    @Test
    void adjustStock_belowZero_rejectedWith409_counterAndLedgerBothUnchanged() {
        AuthResponse admin = registerOrganization("Stock Insufficient Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Installation Service", "1000.00", 5);

        ResponseEntity<String> response = post("/inventory/products/" + productId + "/stock-adjustments",
                admin.accessToken(), Map.of("quantityChange", -10, "note", "Too much"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode unchanged = parse(get("/inventory/products/" + productId, admin.accessToken()).getBody());
        assertThat(unchanged.get("stockQuantity").asInt()).isEqualTo(5);
        Integer ledgerSum = sumQuantityChange(admin.orgId(), productId);
        assertThat(ledgerSum).isEqualTo(5); // only the initial-stock movement from create()
    }

    @Test
    void adjustStock_zeroQuantityChange_rejectedWith400() {
        AuthResponse admin = registerOrganization("Stock Zero Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Solar Inverter 5KVA", "25000.00", 8);

        ResponseEntity<String> response = post("/inventory/products/" + productId + "/stock-adjustments",
                admin.accessToken(), Map.of("quantityChange", 0));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lowStockNotification_firesOnceOnCrossing_notAgainWhileStillLow_andAgainAfterRestockThenReCrossing() {
        AuthResponse admin = registerOrganization("Low Stock Notification Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Solar Inverter 3KVA", "18000.00", 10,
                5 /* lowStockThreshold */);

        // 10 -> 6: still above threshold(5), no notification yet.
        adjust(admin.accessToken(), productId, -4, "Sale 1");
        assertThat(unreadLowStockCount(admin.accessToken())).isEqualTo(0);

        // 6 -> 4: crosses at-or-below 5 for the first time - exactly one notification.
        adjust(admin.accessToken(), productId, -2, "Sale 2 crosses threshold");
        assertThat(unreadLowStockCount(admin.accessToken())).isEqualTo(1);

        // 4 -> 2: still at/below threshold - must NOT fire again while already low.
        adjust(admin.accessToken(), productId, -2, "Sale 3 still low");
        assertThat(unreadLowStockCount(admin.accessToken())).isEqualTo(1);

        // 2 -> 12: restocked back above threshold.
        adjust(admin.accessToken(), productId, 10, "Restocked");

        // 12 -> 4: crosses below threshold again - a genuinely NEW crossing, fires again.
        adjust(admin.accessToken(), productId, -8, "Sale 4 crosses again");
        assertThat(unreadLowStockCount(admin.accessToken())).isEqualTo(2);
    }

    // ---- helpers ----

    /**
     * A direct repository call outside any HTTP request has no ambient TenantContext/RLS
     * session variable set, so it would see zero rows (FORCE ROW LEVEL SECURITY blocks
     * everything) - same TransactionTemplate/activateTenant pattern as
     * EmployeeManagerHierarchyIT#getSubordinates and ReportingIT's tenant-scoped helpers.
     */
    private Integer sumQuantityChange(UUID orgId, String productId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            try {
                tenantSessionManager.activateTenant(orgId);
                return stockMovementRepository.sumQuantityChangeByProductId(UUID.fromString(productId));
            } finally {
                tenantSessionManager.clearTenant();
            }
        });
    }

    private void adjust(String token, String productId, int quantityChange, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantityChange", quantityChange);
        body.put("note", note);
        ResponseEntity<String> response = post("/inventory/products/" + productId + "/stock-adjustments",
                token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long unreadLowStockCount(String token) {
        JsonNode notifications = parse(get("/notifications?unreadOnly=true", token).getBody());
        long count = 0;
        for (JsonNode n : notifications.get("content")) {
            if (n.get("type").asText().equals("LOW_STOCK")) {
                count++;
            }
        }
        return count;
    }

    private String createProduct(String token, String name, String unitPrice, int stockQuantity) {
        return createProduct(token, name, unitPrice, stockQuantity, null);
    }

    private String createProduct(String token, String name, String unitPrice, int stockQuantity,
                                  Integer lowStockThreshold) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("stockQuantity", stockQuantity);
        if (lowStockThreshold != null) {
            body.put("lowStockThreshold", lowStockThreshold);
        }
        ResponseEntity<String> response = post("/inventory/products", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "StockAdjustmentIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + CODE,
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@stockadjustment.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
