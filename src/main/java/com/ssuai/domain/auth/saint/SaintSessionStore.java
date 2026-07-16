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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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

import com.ssuai.domain.auth.mcp.McpProviderHealth;
import com.ssuai.domain.auth.mcp.McpProviderHealthSnapshot;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.SaintSessionExpiredException;

/**
 * Encrypted in-memory store of saint portal cookies captured during the
 * SSO callback (Task 16 PR 16a). Each MCP credential namespace is keyed by the
 * exact owning MCP session id. Web sessions may use their own opaque key.
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
    private final SaintSessionRepository repository;
    private final Map<String, SaintSessionEntry> entries;
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    @Autowired
    public SaintSessionStore(
            SaintSessionProperties properties,
            SaintSessionRepository repository) {
        this(properties, repository, Clock.systemUTC(), new SecureRandom());
    }

    /** Standalone/test constructor; production uses the persistent repository constructor. */
    public SaintSessionStore(SaintSessionProperties properties) {
        this(properties, null, Clock.systemUTC(), new SecureRandom());
    }

    SaintSessionStore(SaintSessionProperties properties, Clock clock, SecureRandom secureRandom) {
        this(properties, null, clock, secureRandom);
    }

    private SaintSessionStore(
            SaintSessionProperties properties,
            SaintSessionRepository repository,
            Clock clock,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.repository = repository;
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
        putForSession(studentId, studentId, cookies);
    }

    /** Stores one credential namespace owned by an exact MCP session. */
    @Transactional
    public void putForSession(String sessionKey, String studentId, PortalCookies cookies) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        if (studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("studentId is required");
        }
        if (cookies == null) {
            throw new IllegalArgumentException("cookies is required");
        }
        Instant now = clock.instant();
        if (repository != null) {
            SaintSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            long version = entity == null ? 1L : entity.getCredentialVersion() + 1L;
            SaintSessionEntry entry = encrypt(cookies.rawCookieHeader(), studentId, now, version);
            EncryptedValue principal = encryptValue(studentId);
            if (entity == null) {
                entity = new SaintSessionEntity(
                        sessionKey,
                        encode(principal.iv()),
                        encode(principal.ciphertext()),
                        encode(entry.iv()),
                        encode(entry.ciphertext()),
                        entry.capturedAt(),
                        entry.expiresAt(),
                        entry.credentialVersion(),
                        entry.health().health().name(),
                        entry.health().lastValidatedAt(),
                        entry.health().lastSuccessfulCallAt(),
                        entry.health().lastFailureAt(),
                        entry.health().failureCode());
            } else {
                entity.updatePrincipal(
                        encode(principal.iv()), encode(principal.ciphertext()));
                entity.updateCredential(
                        encode(entry.iv()),
                        encode(entry.ciphertext()),
                        entry.capturedAt(),
                        entry.expiresAt(),
                        entry.credentialVersion(),
                        entry.health().health().name(),
                        entry.health().lastValidatedAt(),
                        entry.health().lastSuccessfulCallAt(),
                        entry.health().lastFailureAt(),
                        entry.health().failureCode());
            }
            repository.save(entity);
            return;
        }
        synchronized (entries) {
            long version = Optional.ofNullable(entries.get(sessionKey))
                    .map(SaintSessionEntry::credentialVersion)
                    .orElse(0L) + 1L;
            entries.put(sessionKey, encrypt(cookies.rawCookieHeader(), studentId, now, version));
        }
    }

    /**
     * Creates an independently encrypted credential namespace for a newly issued MCP
     * session. The plaintext exists only inside this method and neither key is logged.
     */
    @Transactional
    public boolean copyForSession(String sourceKey, String targetSessionKey) {
        if (targetSessionKey == null || targetSessionKey.isBlank()) {
            throw new IllegalArgumentException("targetSessionKey is required");
        }
        if (repository != null) {
            SaintSessionEntity sourceEntity = repository.findById(sourceKey).orElse(null);
            if (sourceEntity == null) {
                return false;
            }
            SaintSessionEntry source = toEntry(sourceEntity);
            if (!copyable(source)) {
                if (source.expiresAt().isBefore(clock.instant())) {
                    repository.delete(sourceEntity);
                }
                return false;
            }
            SaintSessionEntry copied = reencrypt(source);
            EncryptedValue principal = encryptValue(source.studentId());
            SaintSessionEntity target = repository.findForUpdate(targetSessionKey).orElse(null);
            if (target == null) {
                target = new SaintSessionEntity(
                        targetSessionKey,
                        encode(principal.iv()),
                        encode(principal.ciphertext()),
                        encode(copied.iv()),
                        encode(copied.ciphertext()),
                        copied.capturedAt(),
                        copied.expiresAt(),
                        copied.credentialVersion(),
                        copied.health().health().name(),
                        copied.health().lastValidatedAt(),
                        copied.health().lastSuccessfulCallAt(),
                        copied.health().lastFailureAt(),
                        copied.health().failureCode());
            } else {
                target.updatePrincipal(encode(principal.iv()), encode(principal.ciphertext()));
                target.updateCredential(
                        encode(copied.iv()),
                        encode(copied.ciphertext()),
                        copied.capturedAt(),
                        copied.expiresAt(),
                        copied.credentialVersion(),
                        copied.health().health().name(),
                        copied.health().lastValidatedAt(),
                        copied.health().lastSuccessfulCallAt(),
                        copied.health().lastFailureAt(),
                        copied.health().failureCode());
            }
            repository.save(target);
            return true;
        }
        synchronized (entries) {
            SaintSessionEntry source = entries.get(sourceKey);
            if (source == null || !copyable(source)) {
                if (source != null && source.expiresAt().isBefore(clock.instant())) {
                    entries.remove(sourceKey);
                }
                return false;
            }
            entries.put(targetSessionKey, reencrypt(source));
            return true;
        }
    }

    @Transactional
    public Optional<PortalCookies> cookies(String studentId) {
        return session(studentId).map(SaintProviderSession::cookies);
    }

    /** Returns both the upstream identity and credential snapshot for a store key. */
    @Transactional
    public Optional<SaintProviderSession> session(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        if (repository != null) {
            SaintSessionEntity entity = repository.findById(sessionKey).orElse(null);
            if (entity == null) {
                return Optional.empty();
            }
            if (entity.getExpiresAt().isBefore(clock.instant())) {
                repository.delete(entity);
                return Optional.empty();
            }
            return Optional.of(toProviderSession(entity));
        }
        SaintSessionEntry entry;
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
        return Optional.of(new SaintProviderSession(
                entry.studentId(), new PortalCookies(decrypt(entry)), entry.credentialVersion()));
    }

    public boolean has(String studentId) {
        return cookies(studentId).isPresent();
    }

    @Transactional
    public void invalidate(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return;
        }
        if (repository != null) {
            repository.deleteById(studentId);
        } else {
            synchronized (entries) {
                entries.remove(studentId);
            }
        }
        sessionLocks.remove(studentId);
    }

    /**
     * Serializes mixed SAINT calls for one provider session and updates health atomically.
     * rusaint reconstructs native state from one canonical serialized session, so concurrent
     * endpoint-specific state machines must not race on that session.
     */
    @Transactional
    public <T> T withSession(String sessionKey, Function<SaintProviderSession, T> operation) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            SaintProviderSession providerSession;
            if (repository != null) {
                SaintSessionEntity entity = repository.findForUpdate(sessionKey)
                        .orElseThrow(SaintSessionExpiredException::new);
                if (entity.getExpiresAt().isBefore(clock.instant())) {
                    repository.delete(entity);
                    throw new SaintSessionExpiredException();
                }
                providerSession = toProviderSession(entity);
            } else {
                providerSession = session(sessionKey)
                        .orElseThrow(SaintSessionExpiredException::new);
            }
            try {
                T result = operation.apply(providerSession);
                persistRefreshedState(sessionKey, providerSession);
                markSuccess(sessionKey);
                return result;
            } catch (SaintSessionExpiredException exception) {
                markFailure(sessionKey, McpProviderHealth.EXPIRED, "UPSTREAM_SESSION_EXPIRED");
                throw exception;
            } catch (ConnectorException exception) {
                markFailure(sessionKey, McpProviderHealth.ERROR, exception.getErrorCode().name());
                throw exception;
            } catch (RuntimeException exception) {
                markFailure(sessionKey, McpProviderHealth.ERROR, "INTERNAL_ERROR");
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    public Optional<McpProviderHealthSnapshot> health(String sessionKey) {
        if (repository != null) {
            return repository.findById(sessionKey).map(this::healthOf);
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(sessionKey)).map(SaintSessionEntry::health);
        }
    }

    int size() {
        if (repository != null) {
            return Math.toIntExact(repository.count());
        }
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

    private void markSuccess(String sessionKey) {
        if (repository != null) {
            SaintSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            if (entity == null) {
                return;
            }
            SaintSessionEntry current = toEntry(entity);
            Instant now = clock.instant();
            savePersistent(entity, new SaintSessionEntry(
                    current.iv(), current.ciphertext(), current.studentId(), current.capturedAt(),
                    current.expiresAt(), current.credentialVersion(),
                    new McpProviderHealthSnapshot(
                            McpProviderHealth.VALID, now, now,
                            current.health().lastFailureAt(), null,
                            current.credentialVersion())));
            return;
        }
        synchronized (entries) {
            SaintSessionEntry current = entries.get(sessionKey);
            if (current == null) {
                return;
            }
            Instant now = clock.instant();
            entries.put(sessionKey, new SaintSessionEntry(
                    current.iv(), current.ciphertext(), current.studentId(), current.capturedAt(),
                    current.expiresAt(), current.credentialVersion(),
                    new McpProviderHealthSnapshot(
                            McpProviderHealth.VALID, now, now,
                            current.health().lastFailureAt(), null, current.credentialVersion())));
        }
    }

    private void persistRefreshedState(
            String sessionKey, SaintProviderSession providerSession) {
        if (!providerSession.cookies().wasRefreshed()) {
            return;
        }
        if (repository != null) {
            SaintSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            if (entity == null || entity.getCredentialVersion() != providerSession.credentialVersion()) {
                return;
            }
            SaintSessionEntry current = toEntry(entity);
            savePersistent(entity, refreshedEntry(current, providerSession.cookies().sessionJson()));
            return;
        }
        synchronized (entries) {
            SaintSessionEntry current = entries.get(sessionKey);
            if (current == null
                    || current.credentialVersion() != providerSession.credentialVersion()) {
                return;
            }
            entries.put(sessionKey, refreshedEntry(
                    current, providerSession.cookies().sessionJson()));
        }
    }

    private SaintSessionEntry refreshedEntry(
            SaintSessionEntry current, String refreshedSessionJson) {
        long newVersion = current.credentialVersion() + 1L;
        SaintSessionEntry encrypted = encrypt(
                refreshedSessionJson, current.studentId(), current.capturedAt(), newVersion);
        return new SaintSessionEntry(
                encrypted.iv(), encrypted.ciphertext(), encrypted.studentId(),
                encrypted.capturedAt(), clock.instant().plus(properties.getTtl()),
                newVersion,
                new McpProviderHealthSnapshot(
                        current.health().health(), current.health().lastValidatedAt(),
                        current.health().lastSuccessfulCallAt(),
                        current.health().lastFailureAt(),
                        current.health().failureCode(), newVersion));
    }

    private void markFailure(String sessionKey, McpProviderHealth health, String failureCode) {
        if (repository != null) {
            SaintSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            if (entity == null) {
                return;
            }
            SaintSessionEntry current = toEntry(entity);
            Instant now = clock.instant();
            savePersistent(entity, new SaintSessionEntry(
                    current.iv(), current.ciphertext(), current.studentId(), current.capturedAt(),
                    current.expiresAt(), current.credentialVersion(),
                    new McpProviderHealthSnapshot(
                            health, now, current.health().lastSuccessfulCallAt(),
                            now, failureCode, current.credentialVersion())));
            return;
        }
        synchronized (entries) {
            SaintSessionEntry current = entries.get(sessionKey);
            if (current == null) {
                return;
            }
            Instant now = clock.instant();
            entries.put(sessionKey, new SaintSessionEntry(
                    current.iv(), current.ciphertext(), current.studentId(), current.capturedAt(),
                    current.expiresAt(), current.credentialVersion(),
                    new McpProviderHealthSnapshot(
                            health, now, current.health().lastSuccessfulCallAt(),
                            now, failureCode, current.credentialVersion())));
        }
    }

    private SaintSessionEntry encrypt(
            String plaintext, String studentId, Instant capturedAt, long credentialVersion) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, iv,
                plaintext.getBytes(StandardCharsets.UTF_8));
        return new SaintSessionEntry(
                iv, ciphertext, studentId, capturedAt, capturedAt.plus(properties.getTtl()),
                credentialVersion, McpProviderHealthSnapshot.unknown(credentialVersion));
    }

    private SaintSessionEntry reencrypt(SaintSessionEntry source) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(
                Cipher.ENCRYPT_MODE,
                iv,
                decrypt(source).getBytes(StandardCharsets.UTF_8));
        return new SaintSessionEntry(
                iv,
                ciphertext,
                source.studentId(),
                source.capturedAt(),
                source.expiresAt(),
                source.credentialVersion(),
                source.health());
    }

    private boolean copyable(SaintSessionEntry source) {
        return !source.expiresAt().isBefore(clock.instant())
                && source.health().health() != McpProviderHealth.EXPIRED;
    }

    private String decrypt(SaintSessionEntry entry) {
        byte[] plaintext = runCipher(Cipher.DECRYPT_MODE, entry.iv(), entry.ciphertext());
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private EncryptedValue encryptValue(String plaintext) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        return new EncryptedValue(
                iv,
                runCipher(Cipher.ENCRYPT_MODE, iv, plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private String decryptValue(String ivB64, String ciphertextB64) {
        byte[] plaintext = runCipher(
                Cipher.DECRYPT_MODE, decode(ivB64), decode(ciphertextB64));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private SaintProviderSession toProviderSession(SaintSessionEntity entity) {
        SaintSessionEntry entry = toEntry(entity);
        return new SaintProviderSession(
                entry.studentId(), new PortalCookies(decrypt(entry)), entry.credentialVersion());
    }

    private SaintSessionEntry toEntry(SaintSessionEntity entity) {
        return new SaintSessionEntry(
                decode(entity.getCookieIvB64()),
                decode(entity.getCookieCipherB64()),
                decryptValue(entity.getPrincipalIvB64(), entity.getPrincipalCipherB64()),
                entity.getCapturedAt(),
                entity.getExpiresAt(),
                entity.getCredentialVersion(),
                healthOf(entity));
    }

    private McpProviderHealthSnapshot healthOf(SaintSessionEntity entity) {
        return new McpProviderHealthSnapshot(
                McpProviderHealth.valueOf(entity.getHealth()),
                entity.getLastValidatedAt(),
                entity.getLastSuccessfulCallAt(),
                entity.getLastFailureAt(),
                entity.getFailureCode(),
                entity.getCredentialVersion());
    }

    private void savePersistent(SaintSessionEntity entity, SaintSessionEntry entry) {
        entity.updateCredential(
                encode(entry.iv()),
                encode(entry.ciphertext()),
                entry.capturedAt(),
                entry.expiresAt(),
                entry.credentialVersion(),
                entry.health().health().name(),
                entry.health().lastValidatedAt(),
                entry.health().lastSuccessfulCallAt(),
                entry.health().lastFailureAt(),
                entry.health().failureCode());
        repository.save(entity);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanupExpiredSessions() {
        if (repository != null) {
            repository.deleteExpiredBefore(clock.instant());
        }
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
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

    public record SaintProviderSession(String studentId, PortalCookies cookies, long credentialVersion) {
    }

    private record EncryptedValue(byte[] iv, byte[] ciphertext) {
    }
}
