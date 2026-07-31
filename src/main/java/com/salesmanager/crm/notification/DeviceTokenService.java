package com.salesmanager.crm.notification;

import com.salesmanager.crm.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration is always for the CURRENT user's own employeeId - never client-supplied, same
 * golden rule as organizationId elsewhere. An FCM token is only ever valid for one client at a
 * time, so re-registering an already-known token (e.g. the same browser tab calling this again
 * after a token refresh event) upserts in place rather than erroring on the unique constraint.
 */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final CurrentUser currentUser;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository, CurrentUser currentUser) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public DeviceToken register(String token, DevicePlatform platform) {
        UUID employeeId = currentUser.get().getEmployeeId();
        DeviceToken deviceToken = deviceTokenRepository.findByToken(token)
                .orElseGet(() -> DeviceToken.builder().token(token).build());
        deviceToken.setEmployeeId(employeeId);
        deviceToken.setPlatform(platform);
        deviceToken.setLastSeenAt(OffsetDateTime.now());
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        return deviceTokenRepository.saveAndFlush(deviceToken);
    }

    /** No-op (not an error) if the token isn't registered, OR if it belongs to a different
     * employee - same "empty state, not a bug" idiom used elsewhere (e.g.
     * NotificationService#markAllReadForCurrentUser), and the same information-hiding principle
     * as everywhere else in this codebase (never reveal whether a resource exists under someone
     * else's account). Without this ownership check, any authenticated+entitled employee in the
     * same org could unregister a colleague's device token, since {@code token} alone carries no
     * per-employee scoping on its own. */
    @Transactional
    public void unregister(String token) {
        UUID employeeId = currentUser.get().getEmployeeId();
        deviceTokenRepository.findByToken(token)
                .filter(deviceToken -> deviceToken.getEmployeeId().equals(employeeId))
                .ifPresent(deviceTokenRepository::delete);
    }

    /** Used only by PushNotificationEventListener - not exposed to any controller. */
    @Transactional(readOnly = true)
    public List<DeviceToken> findByEmployeeId(UUID employeeId) {
        return deviceTokenRepository.findByEmployeeId(employeeId);
    }
}
