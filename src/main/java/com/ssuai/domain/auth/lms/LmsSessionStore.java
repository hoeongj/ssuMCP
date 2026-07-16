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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.ConnectorException;
import org.springframework.transaction.annotation.Propagation;

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
    private final LmsSessionRepository repository;
    private final LmsSessionTransactions transactionRunner;
    private final Map<String, LmsSessionEntry> entries;
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    @Autowired
    public LmsSessionStore(
            LmsSessionProperties properties,
            LmsSessionRepository repository,
            LmsSessionTransactions transactionRunner) {
        this(properties, repository, transactionRunner, Clock.systemUTC(), new SecureRandom());
    }

    /** Compatibility constructor used by persistence integration tests. */
    LmsSessionStore(LmsSessionProperties properties, LmsSessionRepository repository) {
        this(properties, repository, null, Clock.systemUTC(), new SecureRandom());
    }

    /** Standalone/test constructor; production uses the persistent repository constructor. */
    public LmsSessionStore(LmsSessionProperties properties) {
        this(properties, null, null, Clock.systemUTC(), new SecureRandom());
    }

    LmsSessionStore(LmsSessionProperties properties, Clock clock, SecureRandom secureRandom) {
        this(properties, null, null, clock, secureRandom);
    }

    private LmsSessionStore(
            LmsSessionProperties properties,
            LmsSessionRepository repository,
            LmsSessionTransactions transactionRunner,
            Clock clock,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.repository = repository;
        this.transactionRunner = transactionRunner;
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
        putForSession(studentId, studentId, cookies);
    }

    /** Stores one canonical cookie jar owned by an exact MCP session. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void putForSession(String sessionKey, String studentId, LmsCookies cookies) {
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
            LmsSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            long version = entity == null ? 1L : entity.getCredentialVersion() + 1L;
            LmsCookieJar jar = jarForWrite(cookies);
            LmsSessionEntry entry = encrypt(
                    jar.serialize(), studentId, now, version, jar.versions());
            EncryptedValue principal = encryptValue(studentId);
            if (entity == null) {
                entity = new LmsSessionEntity(
                        sessionKey,
                        encode(principal.iv()),
                        encode(principal.ciphertext()),
                        encode(entry.iv()),
                        encode(entry.ciphertext()),
                        entry.capturedAt(),
                        entry.expiresAt(),
                        entry.credentialVersion(),
                        serializeCookieVersions(entry.cookieVersions()),
                        entry.health().health().name(),
                        entry.health().lastValidatedAt(),
                        entry.health().lastSuccessfulCallAt(),
                        entry.health().lastFailureAt(),
                        entry.health().failureCode());
            } else {
                entity.updatePrincipal(
                        encode(principal.iv()), encode(principal.ciphertext()));
                updatePersistent(entity, entry);
            }
            repository.save(entity);
            return;
        }
        synchronized (entries) {
            long version = Optional.ofNullable(entries.get(sessionKey))
                    .map(LmsSessionEntry::credentialVersion)
                    .orElse(0L) + 1L;
            LmsCookieJar jar = jarForWrite(cookies);
            entries.put(sessionKey, encrypt(jar.serialize(), studentId, now, version, jar.versions()));
        }
    }

    /** Creates an independently encrypted credential namespace owned by a new MCP session. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean copyForSession(String sourceKey, String targetSessionKey) {
        if (targetSessionKey == null || targetSessionKey.isBlank()) {
            throw new IllegalArgumentException("targetSessionKey is required");
        }
        if (repository != null) {
            LmsSessionEntity sourceEntity = repository.findById(sourceKey).orElse(null);
            if (sourceEntity == null) {
                return false;
            }
            LmsSessionEntry source;
            try {
                source = toEntry(sourceEntity);
                LmsCookieJar.fromSerialized(decrypt(source));
            } catch (IllegalArgumentException exception) {
                repository.delete(sourceEntity);
                return false;
            }
            if (!copyable(source)) {
                if (source.expiresAt().isBefore(clock.instant())) {
                    repository.delete(sourceEntity);
                }
                return false;
            }
            LmsSessionEntry copied = reencrypt(source);
            EncryptedValue principal = encryptValue(source.studentId());
            LmsSessionEntity target = repository.findForUpdate(targetSessionKey).orElse(null);
            if (target == null) {
                target = new LmsSessionEntity(
                        targetSessionKey,
                        encode(principal.iv()),
                        encode(principal.ciphertext()),
                        encode(copied.iv()),
                        encode(copied.ciphertext()),
                        copied.capturedAt(),
                        copied.expiresAt(),
                        copied.credentialVersion(),
                        serializeCookieVersions(copied.cookieVersions()),
                        copied.health().health().name(),
                        copied.health().lastValidatedAt(),
                        copied.health().lastSuccessfulCallAt(),
                        copied.health().lastFailureAt(),
                        copied.health().failureCode());
            } else {
                target.updatePrincipal(encode(principal.iv()), encode(principal.ciphertext()));
                updatePersistent(target, copied);
            }
            repository.save(target);
            return true;
        }
        synchronized (entries) {
            LmsSessionEntry source = entries.get(sourceKey);
            if (source == null || !copyable(source)) {
                if (source != null && source.expiresAt().isBefore(clock.instant())) {
                    entries.remove(sourceKey);
                }
                return false;
            }
            try {
                LmsCookieJar.fromSerialized(decrypt(source));
            } catch (IllegalArgumentException exception) {
                entries.remove(sourceKey);
                return false;
            }
            entries.put(targetSessionKey, reencrypt(source));
            return true;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LmsCookies> cookies(String studentId) {
        return session(studentId).map(LmsProviderSession::cookies);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LmsProviderSession> session(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        if (repository != null) {
            LmsSessionEntity entity = repository.findById(sessionKey).orElse(null);
            if (entity == null) {
                return Optional.empty();
            }
            if (entity.getExpiresAt().isBefore(clock.instant())) {
                repository.delete(entity);
                return Optional.empty();
            }
            try {
                return Optional.of(toProviderSession(entity));
            } catch (IllegalArgumentException exception) {
                // Pre-versioned/raw encrypted payloads have no trustworthy scope metadata.
                repository.delete(entity);
                return Optional.empty();
            }
        }
        LmsSessionEntry entry;
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
        try {
            return Optional.of(toProviderSession(entry, sessionKey));
        } catch (IllegalArgumentException exception) {
            synchronized (entries) {
                entries.remove(sessionKey);
            }
            return Optional.empty();
        }
    }

    public boolean has(String studentId) {
        return cookies(studentId).isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

    /** Serializes mixed LMS calls for one canonical provider session. */
    public <T> T withSession(String sessionKey, Function<LmsProviderSession, T> operation) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            LmsProviderSession providerSession;
            providerSession = session(sessionKey).orElseThrow(LmsSessionExpiredException::new);
            try {
                T result = operation.apply(providerSession);
                markSuccess(sessionKey);
                return result;
            } catch (LmsSessionExpiredException exception) {
                markFailure(sessionKey, McpProviderHealth.EXPIRED, "UPSTREAM_SESSION_EXPIRED");
                throw exception;
            } catch (LmsApiException exception) {
                String code = exception.getStatusCode() >= 500
                        ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_PROTOCOL_CHANGED";
                markFailure(sessionKey, McpProviderHealth.ERROR, code);
                throw exception;
            } catch (ConnectorException exception) {
                String code = switch (exception.getErrorCode()) {
                    case CONNECTOR_PARSE_ERROR -> "PARSER_ERROR";
                    case CONNECTOR_TIMEOUT -> "NETWORK_ERROR";
                    case CONNECTOR_UNAVAILABLE, CIRCUIT_OPEN -> "UPSTREAM_UNAVAILABLE";
                    case UPSTREAM_RATE_LIMITED -> "RATE_LIMITED";
                    default -> "UPSTREAM_PROTOCOL_CHANGED";
                };
                markFailure(sessionKey, McpProviderHealth.ERROR, code);
                throw exception;
            } catch (RuntimeException exception) {
                markFailure(sessionKey, McpProviderHealth.ERROR, "NETWORK_ERROR");
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically merges response Set-Cookie values into the newest cookie jar.
     * Non-conflicting updates from stale snapshots are preserved; a stale response may not
     * overwrite a cookie name already changed by a newer credential version.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LmsCookies mergeSetCookie(LmsCookies snapshot, List<String> setCookieHeaders) {
        if (snapshot == null || snapshot.cookieJarPayload() == null || setCookieHeaders == null) {
            return snapshot;
        }
        LmsCookieJar observed = LmsCookieJar.fromSerialized(snapshot.cookieJarPayload()).copy();
        java.net.URI origin = observed.allowedOrigins().stream().findFirst()
                .map(java.net.URI::create)
                .orElseThrow(() -> new IllegalArgumentException("missing LMS origin policy"));
        for (String header : setCookieHeaders) {
            observed.applySetCookie(origin, header);
        }
        return mergeCookieJar(snapshot, observed.serialize());
    }

    /**
     * Persists a response-side cookie jar in its own transaction.  This is invoked before
     * response classification, so a later parsing/auth failure cannot roll back Set-Cookie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LmsCookies mergeCookieJar(LmsCookies snapshot, String observedJarPayload) {
        if (snapshot == null || snapshot.sessionKey() == null || snapshot.sessionKey().isBlank()
                || observedJarPayload == null || observedJarPayload.isBlank()) {
            return snapshot;
        }
        if (repository != null) {
            LmsSessionEntity entity = repository.findForUpdate(snapshot.sessionKey())
                    .orElseThrow(LmsSessionExpiredException::new);
            if (entity.getExpiresAt().isBefore(clock.instant())) {
                repository.delete(entity);
                throw new LmsSessionExpiredException();
            }
            MergeOutcome outcome = mergeInto(toEntry(entity), snapshot, observedJarPayload);
            if (outcome.changed()) {
                updatePersistent(entity, outcome.entry());
                repository.save(entity);
            }
            return outcome.cookies();
        }
        synchronized (entries) {
            LmsSessionEntry current = entries.get(snapshot.sessionKey());
            if (current == null || current.expiresAt().isBefore(clock.instant())) {
                throw new LmsSessionExpiredException();
            }
            MergeOutcome outcome = mergeInto(current, snapshot, observedJarPayload);
            if (outcome.changed()) {
                entries.put(snapshot.sessionKey(), outcome.entry());
            }
            return outcome.cookies();
        }
    }

    private MergeOutcome mergeInto(
            LmsSessionEntry current,
            LmsCookies snapshot,
            String observedJarPayload) {
        LmsCookieJar jar = LmsCookieJar.fromSerialized(decrypt(current));
        LmsCookieJar snapshotJar = LmsCookieJar.fromSerialized(snapshot.cookieJarPayload());
        LmsCookieJar observed = LmsCookieJar.fromSerialized(observedJarPayload);
        long newVersion = current.credentialVersion() + 1L;
        if (!jar.mergeChangedFrom(snapshotJar, observed, newVersion)) {
            return new MergeOutcome(current, cookiesFor(jar, snapshot.sessionKey(),
                    current.credentialVersion()), false);
        }

        String payload = jar.serialize();
        Instant now = clock.instant();
        LmsSessionEntry encrypted = encrypt(payload, current.studentId(), current.capturedAt(),
                newVersion, jar.versions());
        LmsSessionEntry updated = new LmsSessionEntry(
                encrypted.iv(), encrypted.ciphertext(), encrypted.studentId(),
                encrypted.capturedAt(), now.plus(properties.getTtl()),
                encrypted.credentialVersion(), encrypted.cookieVersions(),
                new McpProviderHealthSnapshot(
                        current.health().health(), now,
                        current.health().lastSuccessfulCallAt(),
                        current.health().lastFailureAt(),
                        current.health().failureCode(), newVersion));
        return new MergeOutcome(
                updated, cookiesFor(jar, snapshot.sessionKey(), newVersion), true);
    }

    public Optional<McpProviderHealthSnapshot> health(String sessionKey) {
        if (repository != null) {
            return repository.findById(sessionKey).map(this::healthOf);
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(sessionKey)).map(LmsSessionEntry::health);
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
        if (repository != null && transactionRunner != null) {
            transactionRunner.inNewTransaction(() -> {
                markSuccessInTransaction(sessionKey);
                return null;
            });
            return;
        }
        markSuccessInTransaction(sessionKey);
    }

    private void markSuccessInTransaction(String sessionKey) {
        if (repository != null) {
            LmsSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            if (entity == null) {
                return;
            }
            LmsSessionEntry current = toEntry(entity);
            Instant now = clock.instant();
            savePersistent(entity, copyWithHealth(current, new McpProviderHealthSnapshot(
                    McpProviderHealth.VALID, now, now,
                    current.health().lastFailureAt(), null,
                    current.credentialVersion())));
            return;
        }
        synchronized (entries) {
            LmsSessionEntry current = entries.get(sessionKey);
            if (current == null) {
                return;
            }
            Instant now = clock.instant();
            entries.put(sessionKey, copyWithHealth(current, new McpProviderHealthSnapshot(
                    McpProviderHealth.VALID, now, now,
                    current.health().lastFailureAt(), null, current.credentialVersion())));
        }
    }

    private void markFailure(String sessionKey, McpProviderHealth health, String failureCode) {
        if (repository != null && transactionRunner != null) {
            transactionRunner.inNewTransaction(() -> {
                markFailureInTransaction(sessionKey, health, failureCode);
                return null;
            });
            return;
        }
        markFailureInTransaction(sessionKey, health, failureCode);
    }

    private void markFailureInTransaction(String sessionKey, McpProviderHealth health, String failureCode) {
        if (repository != null) {
            LmsSessionEntity entity = repository.findForUpdate(sessionKey).orElse(null);
            if (entity == null) {
                return;
            }
            LmsSessionEntry current = toEntry(entity);
            Instant now = clock.instant();
            savePersistent(entity, copyWithHealth(current, new McpProviderHealthSnapshot(
                    health, now, current.health().lastSuccessfulCallAt(),
                    now, failureCode, current.credentialVersion())));
            return;
        }
        synchronized (entries) {
            LmsSessionEntry current = entries.get(sessionKey);
            if (current == null) {
                return;
            }
            Instant now = clock.instant();
            entries.put(sessionKey, copyWithHealth(current, new McpProviderHealthSnapshot(
                    health, now, current.health().lastSuccessfulCallAt(),
                    now, failureCode, current.credentialVersion())));
        }
    }

    private static LmsSessionEntry copyWithHealth(
            LmsSessionEntry current, McpProviderHealthSnapshot health) {
        return new LmsSessionEntry(
                current.iv(), current.ciphertext(), current.studentId(), current.capturedAt(),
                current.expiresAt(), current.credentialVersion(), current.cookieVersions(), health);
    }

    private LmsSessionEntry encrypt(
            String plaintext,
            String studentId,
            Instant capturedAt,
            long credentialVersion,
            Map<String, Long> cookieVersions) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(Cipher.ENCRYPT_MODE, iv,
                plaintext.getBytes(StandardCharsets.UTF_8));
        return new LmsSessionEntry(
                iv, ciphertext, studentId, capturedAt, capturedAt.plus(properties.getTtl()),
                credentialVersion, cookieVersions,
                McpProviderHealthSnapshot.unknown(credentialVersion));
    }

    private LmsSessionEntry reencrypt(LmsSessionEntry source) {
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] ciphertext = runCipher(
                Cipher.ENCRYPT_MODE,
                iv,
                decrypt(source).getBytes(StandardCharsets.UTF_8));
        return new LmsSessionEntry(
                iv,
                ciphertext,
                source.studentId(),
                source.capturedAt(),
                source.expiresAt(),
                source.credentialVersion(),
                source.cookieVersions(),
                source.health());
    }

    private boolean copyable(LmsSessionEntry source) {
        return !source.expiresAt().isBefore(clock.instant())
                && source.health().health() != McpProviderHealth.EXPIRED;
    }

    private String decrypt(LmsSessionEntry entry) {
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

    private LmsProviderSession toProviderSession(LmsSessionEntity entity) {
        LmsSessionEntry entry = toEntry(entity);
        return toProviderSession(entry, entity.getSessionKey());
    }

    private LmsProviderSession toProviderSession(LmsSessionEntry entry, String sessionKey) {
        LmsCookieJar jar = LmsCookieJar.fromSerialized(decrypt(entry));
        return new LmsProviderSession(
                entry.studentId(),
                cookiesFor(jar, sessionKey, entry.credentialVersion()),
                entry.credentialVersion());
    }

    private LmsSessionEntry toEntry(LmsSessionEntity entity) {
        return new LmsSessionEntry(
                decode(entity.getCookieIvB64()),
                decode(entity.getCookieCipherB64()),
                decryptValue(entity.getPrincipalIvB64(), entity.getPrincipalCipherB64()),
                entity.getCapturedAt(),
                entity.getExpiresAt(),
                entity.getCredentialVersion(),
                parseCookieVersions(entity.getCookieVersions()),
                healthOf(entity));
    }

    private McpProviderHealthSnapshot healthOf(LmsSessionEntity entity) {
        return new McpProviderHealthSnapshot(
                McpProviderHealth.valueOf(entity.getHealth()),
                entity.getLastValidatedAt(),
                entity.getLastSuccessfulCallAt(),
                entity.getLastFailureAt(),
                entity.getFailureCode(),
                entity.getCredentialVersion());
    }

    private void updatePersistent(LmsSessionEntity entity, LmsSessionEntry entry) {
        entity.updateCredential(
                encode(entry.iv()),
                encode(entry.ciphertext()),
                entry.capturedAt(),
                entry.expiresAt(),
                entry.credentialVersion(),
                serializeCookieVersions(entry.cookieVersions()),
                entry.health().health().name(),
                entry.health().lastValidatedAt(),
                entry.health().lastSuccessfulCallAt(),
                entry.health().lastFailureAt(),
                entry.health().failureCode());
    }

    private void savePersistent(LmsSessionEntity entity, LmsSessionEntry entry) {
        updatePersistent(entity, entry);
        repository.save(entity);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanupExpiredSessions() {
        if (repository != null) {
            repository.deleteExpiredBefore(clock.instant());
        }
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

    private static LmsCookieJar jarForWrite(LmsCookies cookies) {
        if (cookies.cookieJarPayload() != null && !cookies.cookieJarPayload().isBlank()) {
            return LmsCookieJar.fromSerialized(cookies.cookieJarPayload());
        }
        return LmsCookieJar.fromLegacyHeader(cookies.rawCookieHeader(),
                java.net.URI.create("https://canvas.ssu.ac.kr/"));
    }

    private static LmsCookies cookiesFor(LmsCookieJar jar, String sessionKey, long version) {
        String raw = jar.compatibilityHeader();
        return new LmsCookies(raw, sessionKey, version, jar.serialize());
    }

    private static String serializeCookieVersions(Map<String, Long> versions) {
        return versions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Base64.getUrlEncoder().withoutPadding().encodeToString(
                                entry.getKey().getBytes(StandardCharsets.UTF_8))
                        + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Map<String, Long> parseCookieVersions(String serialized) {
        Map<String, Long> result = new HashMap<>();
        if (serialized == null || serialized.isBlank()) {
            return result;
        }
        for (String line : serialized.split("\\R")) {
            int separator = line.lastIndexOf(':');
            if (separator <= 0 || separator == line.length() - 1) {
                continue;
            }
            try {
                String name = new String(
                        Base64.getUrlDecoder().decode(line.substring(0, separator)),
                        StandardCharsets.UTF_8);
                result.put(name, Long.parseLong(line.substring(separator + 1)));
            } catch (IllegalArgumentException ignored) {
                // Fail closed per cookie: malformed metadata cannot authorize an overwrite.
            }
        }
        return result;
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    public record LmsProviderSession(String studentId, LmsCookies cookies, long credentialVersion) {
    }

    private record EncryptedValue(byte[] iv, byte[] ciphertext) {
    }

    private record MergeOutcome(
            LmsSessionEntry entry, LmsCookies cookies, boolean changed) {
    }
}
