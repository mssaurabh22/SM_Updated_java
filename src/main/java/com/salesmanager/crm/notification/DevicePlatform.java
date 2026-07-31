package com.salesmanager.crm.notification;

/**
 * WEB is delivered today via the Firebase JS SDK + service worker. ANDROID/IOS exist now purely
 * so the schema doesn't need a migration later - nothing registers either yet, since no Flutter
 * (or other native) client exists (see the plan's Section 9/14) - PushNotificationService sends
 * to any of the three identically via the Firebase Admin SDK regardless.
 */
public enum DevicePlatform {
    WEB,
    ANDROID,
    IOS
}
