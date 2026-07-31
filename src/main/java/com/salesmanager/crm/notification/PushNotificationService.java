package com.salesmanager.crm.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps the Firebase Admin SDK. Deliberately self-contained (no separate config class) -
 * initializes its own {@link FirebaseApp} lazily from {@code firebase.service-account-json},
 * and simply does nothing (never throws) if that's blank, so local dev without Firebase
 * credentials configured doesn't crash - see the class-level {@code enabled} flag below.
 *
 * {@code firebase.service-account-json} may be EITHER the raw JSON (fine in application.yml/
 * application-local.yml, which don't reinterpret backslash escapes) OR that JSON base64-encoded
 * (required for the production systemd EnvironmentFile= - it DOES unescape C-style sequences
 * like the private key's embedded {@code \n}, turning them into real newlines that make the
 * JSON invalid and silently truncate the private key, which surfaced as a genuine "Invalid
 * PKCS#8 data" failure in production before this base64 path was added). Detected by whether
 * the trimmed value starts with '{' - a raw JSON object - or not.
 *
 * Sends a DATA-only message (no {@code Notification} block) - the actual title/body text is
 * built client-side (service worker / foreground handler) from the same describeNotification()
 * logic the in-app Notification Center already uses, so there is exactly one place that knows
 * how to render each NotificationType's message, not two.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final boolean enabled;

    public PushNotificationService(DeviceTokenRepository deviceTokenRepository,
                                    @Value("${firebase.service-account-json:}") String serviceAccountJson) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.enabled = initializeFirebaseApp(serviceAccountJson);
    }

    private boolean initializeFirebaseApp(String rawConfigValue) {
        if (rawConfigValue == null || rawConfigValue.isBlank()) {
            log.warn("firebase.service-account-json not configured - push notifications disabled");
            return false;
        }
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                return true;
            }
            String serviceAccountJson = decodeIfBase64(rawConfigValue);
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
            // GoogleCredentials.fromStream doesn't surface project_id back out on its own -
            // FirebaseOptions needs it set explicitly, or Admin SDK calls fail to resolve which
            // Firebase project they're targeting.
            String projectId = new ObjectMapper().readTree(serviceAccountJson).path("project_id").asText(null);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase initialized for project {} - push notifications enabled", projectId);
            return true;
        } catch (Exception e) {
            // Never fatal - a broken/missing Firebase credential must not prevent the whole
            // application from starting, since push is an enhancement, not core functionality.
            log.warn("Failed to initialize Firebase - push notifications disabled", e);
            return false;
        }
    }

    private static String decodeIfBase64(String value) {
        if (value.trim().startsWith("{")) {
            return value;
        }
        return new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8);
    }

    /**
     * Sends the same data payload to every one of the recipient's registered devices. Never
     * throws - a per-token failure is logged and, for a dead token (UNREGISTERED/
     * INVALID_ARGUMENT - the two FCM error codes that mean "this token will never work again"),
     * the corresponding device_tokens row is deleted so it stops being retried on every future
     * notification.
     *
     * Deliberately queries device_tokens regardless of {@code enabled} - only the actual
     * outbound Firebase call in send() is skipped when unconfigured. This keeps the
     * tenant-scoped repository read on the same code path in every environment (test or real),
     * rather than a no-op-when-disabled early return hiding it entirely - that exact shortcut is
     * what let a real bug (this listener chain running with no active TenantContext, so RLS
     * silently returned zero tokens) ship undetected, since every automated test runs with
     * Firebase unconfigured. See DeviceTokenIT's test asserting on this directly.
     */
    public void sendToEmployee(UUID employeeId, UUID notificationId, NotificationType type, String payload) {
        List<DeviceToken> tokens = deviceTokenRepository.findByEmployeeId(employeeId);
        for (DeviceToken deviceToken : tokens) {
            send(deviceToken, notificationId, type, payload);
        }
    }

    private void send(DeviceToken deviceToken, UUID notificationId, NotificationType type, String payload) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, String> data = new HashMap<>();
            data.put("notificationId", notificationId.toString());
            data.put("type", type.name());
            data.put("payload", payload != null ? payload : "");
            Message message = Message.builder()
                    .setToken(deviceToken.getToken())
                    .putAllData(data)
                    .build();
            FirebaseMessaging.getInstance().send(message);
            log.debug("Push sent to device token {}", deviceToken.getId());
        } catch (FirebaseMessagingException e) {
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("Removing dead device token {} ({}: {})",
                        deviceToken.getId(), e.getMessagingErrorCode(), e.getMessage());
                deviceTokenRepository.deleteByToken(deviceToken.getToken());
            } else {
                log.warn("Failed to send push notification to device token {}: {}",
                        deviceToken.getId(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Unexpected error sending push notification to device token {}", deviceToken.getId(), e);
        }
    }
}
