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
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent store of captured library session tokens, keyed by ssuAI session id
 * (Spring HttpSession id for MVP). Values are the upstream {@code Pyxis-Auth-Token}
 * captured from oasis.ssu.ac.kr after the user logs in on its own page.
 *
 * Tokens are encrypted with AES-GCM while stored and decrypted only when the
 * connector needs to drive an upstream {@code Pyxis-Auth-Token} request header.
 * All log messages use the 8-char fingerprint via {@link #fingerprint(String)}.
 */
@Component
public class LibrarySessionStore {

    private static final Logger log = LoggerFactory.getLogger(LibrarySessionStore.class);
    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final LibrarySessionRepository repository;
    private final LibrarySessionProperties properties;
    private final Clock clock;
    private final SecretKey aesKey;
    private final SecureRandom secureRandom;

    @Autowired
    public LibrarySessionStore(
            LibrarySessionRepository repository,
            LibrarySessionProperties properties
    ) {
        this(repository, properties, Clock.systemUTC(), new SecureRandom());
    }

    LibrarySessionStore(
            LibrarySessionRepository repository,
            LibrarySessionProperties properties,
            Clock clock
    ) {
        this(repository, properties, clock, new SecureRandom());
    }

    LibrarySessionStore(
            LibrarySessionRepository repository,
            LibrarySessionProperties properties,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.aesKey = buildAesKey(properties.getEncryptionKey(), secureRandom);
    }

    @Transactional
    public void put(String sessionKey, String token) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        Instant now = clock.instant();
        Entry entry = encrypt(token, now);
        repository.save(new LibrarySessionEntity(
                sessionKey,
                Base64.getEncoder().encodeToString(entry.iv()),
                Base64.getEncoder().encodeToString(entry.ciphertext()),
                entry.capturedAt(),
                entry.expiresAt()
        ));
    }

    @Transactional
    public Optional<String> token(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        LibrarySessionEntity entity = repository.findById(sessionKey).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        if (entity.getExpiresAt().isBefore(clock.instant())) {
            repository.deleteById(sessionKey);
            return Optional.empty();
        }
        Entry entry = new Entry(
                Base64.getDecoder().decode(entity.getIvB64()),
                Base64.getDecoder().decode(entity.getCipherB64()),
                entity.getCapturedAt(),
                entity.getExpiresAt()
        );
        return Optional.of(decrypt(entry));
    }

    public boolean has(String sessionKey) {
        return token(sessionKey).isPresent();
    }

    @Transactional
    public void invalidate(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        repository.deleteById(sessionKey);
    }

    int size() {
        return Math.toIntExact(repository.count());
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanupExpiredSessions() {
        repository.deleteExpiredBefore(clock.instant());
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
