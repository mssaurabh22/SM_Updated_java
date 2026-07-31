package com.salesmanager.crm.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByEmployeeId(UUID employeeId);

    /** Unconditional - used only by PushNotificationService's dead-token cleanup (FCM itself
     * confirmed the token will never work again), never by the user-facing unregister endpoint
     * (see DeviceTokenService#unregister's employee-scoped delete instead). */
    void deleteByToken(String token);
}
