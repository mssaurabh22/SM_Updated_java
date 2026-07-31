package com.salesmanager.crm.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for calendar OAuth access/refresh tokens at rest - the first
 * "encrypt sensitive data at rest" need in this codebase (JWT_SECRET/PLATFORM_ADMIN_KEY are
 * verified, not stored, so they've never needed this). Deliberately a small standalone utility,
 * not a generic framework - only calendar_connections uses it today.
 *
 * The raw {@code calendar.token-encryption-key} config value (any length string, same
 * env-var-with-dev-fallback pattern as jwt.secret) is SHA-256 hashed to always derive a proper
 * 32-byte AES-256 key regardless of the input string's length - simpler to configure correctly
 * than requiring an exact-length base64 key, at the minor cost of the effective key strength
 * being bounded by the input string's own entropy, same tradeoff jwt.secret already accepts.
 *
 * Stored format: base64(12-byte random IV || GCM ciphertext+tag) - a fresh random IV per
 * encryption call, prepended so decrypt() never needs a separate IV column.
 */
@Component
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenEncryptionService(@Value("${calendar.token-encryption-key}") String rawKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derivedKey = sha256.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derivedKey, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive calendar token encryption key", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt calendar token", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt calendar token", e);
        }
    }
}
