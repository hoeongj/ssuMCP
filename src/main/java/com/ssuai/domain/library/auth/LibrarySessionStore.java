package com.ssuai.domain.library.auth;

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
 * In-memory store of captured library session tokens, keyed by ssuAI session id
 * (Spring HttpSession id for MVP). Values are the upstream `Pyxis-Auth-Token`
 * captured from oasis.ssu.ac.kr after the user logs in on its own page.
 *
 * Tokens are encrypted with AES-GCM while stored in memory and decrypted only
 * when the connector needs to drive an upstream `Pyxis-Auth-Token` request
 * header. All log messages use the 8-char fingerprint via
 * {@link #fingerprint(String)}.
 */
@Component
public class LibrarySessionStore {

    private static final Logger log = LoggerFactory.getLogger(LibrarySessionStore.class);
    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final LibrarySessionProperties properties;
    private final Clock clock;
    private final SecretKey aesKey;
    private final SecureRandom secureRandom;
    private final Map<String, Entry> entries;

    @Autowired
    public LibrarySessionStore(LibrarySessionProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom());
    }

    public LibrarySessionStore(LibrarySessionProperties properties, Clock clock) {
        this(properties, clock, new SecureRandom());
    }

    LibrarySessionStore(LibrarySessionProperties properties, Clock clock, SecureRandom secureRandom) {
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.aesKey = buildAesKey(properties.getEncryptionKey(), secureRandom);
        int cap = Math.max(1, properties.getMaxSessions());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > cap;
            }
        };
    }

    public void put(String sessionKey, String token) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        Instant now = clock.instant();
        Entry entry = encrypt(token, now);
        synchronized (entries) {
            entries.put(sessionKey, entry);
        }
    }

    public Optional<String> token(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        Entry entry;
        synchronized (entries) {
            entry = entries.get(sessionKey);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expiresAt().isBefore(clock.instant())) {
                entries.remove(sessionKey);
                return Optional.empty();
            }
        }
        return Optional.of(decrypt(entry));
    }

    public boolean has(String sessionKey) {
        return token(sessionKey).isPresent();
    }

    public void invalidate(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        synchronized (entries) {
            entries.remove(sessionKey);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public static String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Entry encrypt(String token, Instant capturedAt) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, aesKey, iv, token.getBytes(StandardCharsets.UTF_8));
        return new Entry(iv, ciphertext, capturedAt, capturedAt.plus(properties.getTtl()));
    }

    private String decrypt(Entry entry) {
        byte[] plaintext = runCipher(Cipher.DECRYPT_MODE, aesKey, entry.iv(), entry.ciphertext());
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static byte[] runCipher(int mode, SecretKey key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to process encrypted library session token", exception);
        }
    }

    private static SecretKey buildAesKey(String configuredKey, SecureRandom secureRandom) {
        byte[] keyBytes;
        if (configuredKey == null || configuredKey.isBlank()) {
            keyBytes = new byte[AES_KEY_BYTES];
            secureRandom.nextBytes(keyBytes);
            log.warn(
                    "ssuai.library.session.encryption-key is empty; generated an ephemeral AES-GCM key. "
                            + "Stored library sessions will be unreadable after restart. "
                            + "Set SSUAI_CREDENTIAL_ENCRYPTION_KEY in production.");
        } else {
            String trimmed = configuredKey.trim();
            try {
                keyBytes = Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException ignored) {
                keyBytes = trimmed.getBytes(StandardCharsets.UTF_8);
            }
            if (keyBytes.length < AES_KEY_BYTES) {
                throw new IllegalStateException(
                        "ssuai.library.session.encryption-key must be at least 32 bytes for AES-256");
            }
            if (keyBytes.length > AES_KEY_BYTES) {
                byte[] exact = new byte[AES_KEY_BYTES];
                System.arraycopy(keyBytes, 0, exact, 0, AES_KEY_BYTES);
                keyBytes = exact;
            }
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private record Entry(byte[] iv, byte[] ciphertext, Instant capturedAt, Instant expiresAt) {

        Entry {
            if (iv == null || iv.length == 0) {
                throw new IllegalArgumentException("iv required");
            }
            if (ciphertext == null || ciphertext.length == 0) {
                throw new IllegalArgumentException("ciphertext required");
            }
            if (capturedAt == null || expiresAt == null) {
                throw new IllegalArgumentException("timestamps required");
            }
        }
    }
}
