package com.salesmanager.crm.calendar;

import com.salesmanager.crm.calendar.CalendarOAuthService.TokenResult;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.TenantSessionManager;
import com.salesmanager.crm.security.UserPrincipal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the connect/disconnect lifecycle. generateAuthorizeUrl/getStatus/disconnect run
 * within a normal authenticated request (TenantContext already set by JwtAuthFilter/TenantFilter,
 * same as every other service). handleCallback is the one exception - Google/Microsoft redirect
 * the browser here directly (see SecurityConfig's permitAll for this path), so there is no JWT
 * and therefore no ambient TenantContext; the state token recovered from CalendarOAuthStateStore
 * supplies the organizationId to manually activate instead - same technique AuthService#login
 * uses for its own pre-authentication persistence (calling activateTenant as the first statement
 * inside this already-@Transactional method, no TransactionTemplate/REQUIRES_NEW needed - that
 * machinery is only for AFTER_COMMIT listeners and no-ambient-transaction scheduled jobs).
 */
@Service
public class CalendarConnectionService {

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarOAuthService calendarOAuthService;
    private final CalendarOAuthStateStore stateStore;
    private final TokenEncryptionService tokenEncryptionService;
    private final TenantSessionManager tenantSessionManager;
    private final CurrentUser currentUser;

    public CalendarConnectionService(CalendarConnectionRepository calendarConnectionRepository,
                                      CalendarOAuthService calendarOAuthService,
                                      CalendarOAuthStateStore stateStore,
                                      TokenEncryptionService tokenEncryptionService,
                                      TenantSessionManager tenantSessionManager,
                                      CurrentUser currentUser) {
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.calendarOAuthService = calendarOAuthService;
        this.stateStore = stateStore;
        this.tokenEncryptionService = tokenEncryptionService;
        this.tenantSessionManager = tenantSessionManager;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public String generateAuthorizeUrl(CalendarProvider provider) {
        UserPrincipal principal = currentUser.get();
        String state = stateStore.create(principal.getEmployeeId(), principal.getOrganizationId(), provider);
        return calendarOAuthService.buildAuthorizeUrl(provider, state);
    }

    /** Silent no-op (not an exception) on an invalid/expired/replayed state - there is no
     * authenticated caller to report an error to at this point in the flow; the redirect
     * target's own {@code ?calendar=error} query param is how the frontend surfaces failure. */
    @Transactional
    public boolean handleCallback(CalendarProvider provider, String code, String state) {
        CalendarOAuthStateStore.Resolved resolved = stateStore.consume(state, provider);
        if (resolved == null) {
            return false;
        }
        try {
            tenantSessionManager.activateTenant(resolved.organizationId());
            TokenResult tokens = calendarOAuthService.exchangeCode(provider, code);

            CalendarConnection connection = calendarConnectionRepository
                    .findByEmployeeId(resolved.employeeId())
                    .orElseGet(() -> CalendarConnection.builder().employeeId(resolved.employeeId()).build());
            connection.setProvider(provider);
            connection.setAccessTokenEncrypted(tokenEncryptionService.encrypt(tokens.accessToken()));
            connection.setRefreshTokenEncrypted(tokenEncryptionService.encrypt(tokens.refreshToken()));
            connection.setTokenExpiresAt(tokens.expiresAt());
            connection.setConnectedAt(OffsetDateTime.now());
            connection.setLastSyncError(null);
            // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
            calendarConnectionRepository.saveAndFlush(connection);
            return true;
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    @Transactional(readOnly = true)
    public Optional<CalendarConnection> getStatus() {
        return calendarConnectionRepository.findByEmployeeId(currentUser.get().getEmployeeId());
    }

    /** No-op (not an error) if there's nothing connected - same "empty state, not a bug" idiom
     * used elsewhere (e.g. NotificationService#markAllReadForCurrentUser). Does not attempt
     * provider-side token revocation - the user can also revoke access directly from their
     * Google/Microsoft account settings. */
    @Transactional
    public void disconnect() {
        calendarConnectionRepository.findByEmployeeId(currentUser.get().getEmployeeId())
                .ifPresent(calendarConnectionRepository::delete);
    }

    /** Used only by CalendarSyncEventListener - not exposed to any controller. Refreshes the
     * access token first if it's expired/near-expiry, persisting the refreshed token so the
     * next sync doesn't need to refresh again. */
    @Transactional
    public String getValidAccessToken(CalendarConnection connection) {
        if (connection.getTokenExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(2))) {
            return tokenEncryptionService.decrypt(connection.getAccessTokenEncrypted());
        }
        String refreshToken = tokenEncryptionService.decrypt(connection.getRefreshTokenEncrypted());
        TokenResult refreshed = calendarOAuthService.refresh(connection.getProvider(), refreshToken);
        connection.setAccessTokenEncrypted(tokenEncryptionService.encrypt(refreshed.accessToken()));
        connection.setTokenExpiresAt(refreshed.expiresAt());
        calendarConnectionRepository.saveAndFlush(connection);
        return refreshed.accessToken();
    }

    /** Used only by CalendarSyncEventListener to record a sync outcome. */
    @Transactional
    public void recordSyncError(UUID connectionId, String error) {
        calendarConnectionRepository.findById(connectionId)
                .ifPresent(connection -> {
                    connection.setLastSyncError(error);
                    calendarConnectionRepository.saveAndFlush(connection);
                });
    }
}
