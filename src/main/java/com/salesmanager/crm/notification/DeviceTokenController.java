package com.salesmanager.crm.notification;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.notification.dto.DeviceTokenRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - DeviceTokenService always scopes registration to the CURRENT user's own
 * employeeId, same layering as every other feature controller. Gated behind PUSH_NOTIFICATIONS -
 * there is no point registering a token for instant delivery an org hasn't licensed.
 */
@RestController
@RequestMapping("/device-tokens")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.PUSH_NOTIFICATIONS)
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.register(request.token(), request.platform());
    }

    @DeleteMapping("/{token}")
    @RequireEntitlement(FeatureEntitlement.PUSH_NOTIFICATIONS)
    public void unregister(@PathVariable String token) {
        deviceTokenService.unregister(token);
    }
}
