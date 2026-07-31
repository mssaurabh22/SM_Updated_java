package com.salesmanager.crm.calendar;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * One active connection per employee - connecting a different provider replaces the row (see
 * CalendarConnectionService#connect), matching real usage (a rep checks one calendar). Token
 * fields are ciphertext (see TokenEncryptionService) - never logged, never returned in any DTO
 * (CalendarConnectionResponse only exposes provider/connectedAt/lastSyncError).
 */
@Entity
@Table(name = "calendar_connections")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CalendarConnection extends TenantAware {

    @Column(name = "employee_id", nullable = false, unique = true)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CalendarProvider provider;

    @Column(name = "access_token_encrypted", nullable = false, length = 4096)
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", nullable = false, length = 4096)
    private String refreshTokenEncrypted;

    @Column(name = "token_expires_at", nullable = false)
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "calendar_id", nullable = false)
    @Builder.Default
    private String calendarId = "primary";

    @Column(name = "connected_at", nullable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "last_sync_error")
    private String lastSyncError;
}
