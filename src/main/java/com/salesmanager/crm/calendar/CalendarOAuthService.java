package com.salesmanager.crm.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Plain REST OAuth2 authorization-code + refresh-token flow against Google Calendar API and
 * Microsoft Graph (Outlook) - no provider SDK needed for either, since once you have user
 * tokens both are ordinary bearer-token REST APIs (unlike FCM's own server-auth model, which is
 * why PushNotificationService uses the Firebase Admin SDK instead - see its class javadoc).
 */
@Service
public class CalendarOAuthService {

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/calendar.events";

    private static final String OUTLOOK_AUTH_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String OUTLOOK_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String OUTLOOK_SCOPE = "offline_access Calendars.ReadWrite";

    private final RestClient restClient = CalendarHttpClient.create();

    @Value("${calendar.google.client-id}")
    private String googleClientId;
    @Value("${calendar.google.client-secret}")
    private String googleClientSecret;
    @Value("${calendar.google.redirect-uri}")
    private String googleRedirectUri;

    @Value("${calendar.outlook.client-id}")
    private String outlookClientId;
    @Value("${calendar.outlook.client-secret}")
    private String outlookClientSecret;
    @Value("${calendar.outlook.redirect-uri}")
    private String outlookRedirectUri;

    public record TokenResult(String accessToken, String refreshToken, OffsetDateTime expiresAt) {
    }

    public String buildAuthorizeUrl(CalendarProvider provider, String state) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_AUTH_URL
                    + "?client_id=" + encode(googleClientId)
                    + "&redirect_uri=" + encode(googleRedirectUri)
                    + "&response_type=code"
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&scope=" + encode(GOOGLE_SCOPE)
                    + "&state=" + encode(state);
            case OUTLOOK -> OUTLOOK_AUTH_URL
                    + "?client_id=" + encode(outlookClientId)
                    + "&redirect_uri=" + encode(outlookRedirectUri)
                    + "&response_type=code"
                    + "&response_mode=query"
                    + "&scope=" + encode(OUTLOOK_SCOPE)
                    + "&state=" + encode(state);
        };
    }

    /** The FIRST token exchange - Google/Microsoft only return a refresh_token here, never on a
     * later refresh call, so the caller must persist it now. */
    public TokenResult exchangeCode(CalendarProvider provider, String code) {
        String tokenUrl = provider == CalendarProvider.GOOGLE ? GOOGLE_TOKEN_URL : OUTLOOK_TOKEN_URL;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId(provider));
        form.add("client_secret", clientSecret(provider));
        form.add("redirect_uri", redirectUri(provider));
        form.add("grant_type", "authorization_code");
        if (provider == CalendarProvider.OUTLOOK) {
            form.add("scope", OUTLOOK_SCOPE);
        }
        JsonNode body = postForm(tokenUrl, form);
        return new TokenResult(
                body.get("access_token").asText(),
                body.get("refresh_token").asText(),
                OffsetDateTime.now().plusSeconds(body.get("expires_in").asLong()));
    }

    /** Refresh does NOT return a new refresh_token for either provider - the caller must keep
     * using the original one it already has stored. */
    public TokenResult refresh(CalendarProvider provider, String refreshToken) {
        String tokenUrl = provider == CalendarProvider.GOOGLE ? GOOGLE_TOKEN_URL : OUTLOOK_TOKEN_URL;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId(provider));
        form.add("client_secret", clientSecret(provider));
        form.add("grant_type", "refresh_token");
        if (provider == CalendarProvider.OUTLOOK) {
            form.add("scope", OUTLOOK_SCOPE);
        }
        JsonNode body = postForm(tokenUrl, form);
        return new TokenResult(
                body.get("access_token").asText(),
                refreshToken,
                OffsetDateTime.now().plusSeconds(body.get("expires_in").asLong()));
    }

    private JsonNode postForm(String url, MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
    }

    private String clientId(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? googleClientId : outlookClientId;
    }

    private String clientSecret(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? googleClientSecret : outlookClientSecret;
    }

    private String redirectUri(CalendarProvider provider) {
        return provider == CalendarProvider.GOOGLE ? googleRedirectUri : outlookRedirectUri;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
