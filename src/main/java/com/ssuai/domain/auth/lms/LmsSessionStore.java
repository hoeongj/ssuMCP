package com.ssuai.domain.auth.lms;

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
 * Encrypted in-memory store of canvas.ssu.ac.kr session cookies captured
 * during the LMS two-phase auth (SmartID SSO → gw-cb.php → canvas dashboard).
 * Same AES-GCM / LRU / TTL shape as {@code SaintSessionStore}.
 *
 * <p>Cookies are encrypted with AES-GCM (256-bit, per-record 96-bit IV).
 * Key source: {@code ssuai.lms.session.encryption-key} — falls back to
 * the same env var as saint ({@code SSUAI_CREDENTIAL_ENCRYPTION_KEY}) if
 * left blank in properties. Empty → ephemeral random key per JVM start,
 * with a warning.
 *
 * <p>TTL default 2h, matching the canvas {@code xn_api_token} JWT expiry.
 */
@Component
public class LmsSessionStore {

    private static final Logger log = LoggerFactory.getLogger(LmsSessionStore.class);

    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final LmsSessionProperties properties;
    private final Clock clock;
    private final SecretKey aesKey;
    private final SecureRandom secureRandom;
    private final Map<String, LmsSessionEntry> entries;

    @Autowired
    public LmsSessionStore(LmsSessionProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom());
    }

    LmsSessionStore(LmsSessionProperties properties, Clock clock, SecureRandom secureRandom) {
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.aesKey = buildAesKey(properties.getEncryptionKey(), secureRandom);
        int cap = Math.max(1, properties.getMaxSessions());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LmsSessionEntry> eldest) {
                return size() > cap;
            }
        };
    }

    public void put(String studentId, LmsCookies cookies) {
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (cookies == null) {
            throw new IllegalArgumentException("cookies is required");
        }
        Instant now = clock.instant();
        LmsSessionEntry entry = encrypt(cookies.rawCookieHeader(), now);
        synchronized (entries) {
            entries.put(studentId, entry);
        }
    }

    public Optional<LmsCookies> cookies(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return Optional.empty();
        }
        LmsSessionEntry entry;
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
        return Optional.of(new LmsCookies(decrypt(entry)));
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

    private LmsSessionEntry encrypt(String plaintext, Instant capturedAt) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, iv,
                plaintext.getBytes(StandardCharsets.UTF_8));
        return new LmsSessionEntry(iv, ciphertext, capturedAt,
                capturedAt.plus(properties.getTtl()));
    }

    private String decrypt(LmsSessionEntry entry) {
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
            log.warn("ssuai.lms.session.encryption-key is empty — generated an ephemeral random key. "
                    + "Stored LMS sessions will be unreadable after restart. "
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
                    "ssuai.lms.session.encryption-key must decode to at least "
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
