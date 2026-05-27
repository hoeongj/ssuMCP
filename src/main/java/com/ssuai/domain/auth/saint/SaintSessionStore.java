package com.ssuai.domain.auth.saint;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encrypted in-memory store of saint portal cookies captured during the
 * SSO callback (Task 16 PR 16a). Keyed by ssuAI {@code studentId}.
 *
 * <p>Cookies are encrypted with AES-GCM (256-bit key, 96-bit per-record
 * IV, 128-bit tag) at rest in the map. The key is sourced from
 * {@code ssuai.saint.session.encryption-key} (env
 * {@code SSUAI_CREDENTIAL_ENCRYPTION_KEY}). Empty key → ephemeral random
 * per JVM start, with a warning — same convenience pattern as
 * {@code JwtProvider}.
 *
 * <p>TTL ({@code ssuai.saint.session.ttl}, default 30 minutes) is checked
 * lazily on read; expired entries are dropped and reported as
 * {@link Optional#empty()}. There is no background scrubber yet — the
 * LRU cap puts a hard ceiling on memory regardless.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Plaintext {@link PortalCookies} only exists transiently inside
 *       {@link #put(String, PortalCookies)} and
 *       {@link #cookies(String)}; the map itself never holds it.
 *   <li>Never log the raw cookie value or the studentId. {@link #fingerprint(String)}
 *       provides an 8-char SHA-256 hash for correlation in logs.
 *   <li>A GCM authentication failure (e.g., key rotated mid-flight)
 *       throws — there is no fallback decryption path. The caller treats
 *       it as a hard failure, not a cache miss.
 * </ul>
 */
@Component
public class SaintSessionStore {

    private static final Logger log = LoggerFactory.getLogger(SaintSessionStore.class);

    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SaintSessionProperties properties;
    private final Clock clock;
    private final SecretKey aesKey;
    private final SecureRandom secureRandom;
    private final Map<String, SaintSessionEntry> entries;

    @Autowired
    public SaintSessionStore(SaintSessionProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom());
    }

    SaintSessionStore(SaintSessionProperties properties, Clock clock, SecureRandom secureRandom) {
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.aesKey = buildAesKey(properties.getEncryptionKey(), secureRandom);
        int cap = Math.max(1, properties.getMaxSessions());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SaintSessionEntry> eldest) {
                return size() > cap;
            }
        };
    }

    public void put(String studentId, PortalCookies cookies) {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (cookies == null) {
            throw new IllegalArgumentException("cookies is required");
        }
        Instant now = clock.instant();
        SaintSessionEntry entry = encrypt(cookies.rawCookieHeader(), now);
        synchronized (entries) {
            entries.put(studentId, entry);
        }
    }

    public Optional<PortalCookies> cookies(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return Optional.empty();
        }
        SaintSessionEntry entry;
        synchronized (entries) {
            entry = entries.get(studentId);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expiresAt().isBefore(clock.instant())) {
                entries.remove(studentId);
                return Optional.empty();
            }
        }
        return Optional.of(new PortalCookies(decrypt(entry)));
    }

    public boolean has(String studentId) {
        return cookies(studentId).isPresent();
    }

    public void invalidate(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return;
        }
        synchronized (entries) {
            entries.remove(studentId);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private SaintSessionEntry encrypt(String plaintext, Instant capturedAt) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, iv,
                plaintext.getBytes(StandardCharsets.UTF_8));
        return new SaintSessionEntry(iv, ciphertext, capturedAt,
                capturedAt.plus(properties.getTtl()));
    }

    private String decrypt(SaintSessionEntry entry) {
        byte[] plaintext = runCipher(Cipher.DECRYPT_MODE, entry.iv(), entry.ciphertext());
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private byte[] runCipher(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(mode, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM operation failed", exception);
        }
    }

    private static SecretKey buildAesKey(String configuredKey, SecureRandom secureRandom) {
        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] random = new byte[AES_KEY_BYTES];
            secureRandom.nextBytes(random);
            log.warn("ssuai.saint.session.encryption-key is empty — generated an ephemeral random key. "
                    + "Stored saint sessions will be unreadable after restart. "
                    + "Set SSUAI_CREDENTIAL_ENCRYPTION_KEY (>= 32 bytes, base64 recommended) for non-dev.");
            return new SecretKeySpec(random, "AES");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException ignored) {
            decoded = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        if (decoded.length < AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "ssuai.saint.session.encryption-key must decode to at least "
                            + AES_KEY_BYTES + " bytes for AES-256");
        }
        if (decoded.length > AES_KEY_BYTES) {
            byte[] truncated = new byte[AES_KEY_BYTES];
            System.arraycopy(decoded, 0, truncated, 0, AES_KEY_BYTES);
            decoded = truncated;
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
