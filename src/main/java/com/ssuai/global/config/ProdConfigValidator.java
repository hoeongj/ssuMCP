package com.ssuai.global.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.ssuai.domain.auth.saint.SaintSessionProperties;
import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.domain.chat.config.LlmChatProperties.DirectProvider;
import com.ssuai.global.auth.JwtProperties;

/**
 * Fails the prod boot fast when a required production secret/config is missing
 * or has silently fallen back to an insecure dev default.
 *
 * <p><b>Why this exists (Codex #19).</b> Several production-critical values are
 * read via {@code ${ENV:default}} with dev-friendly empty/insecure defaults:
 * {@code SSUAI_DB_URL} defaults to an in-memory H2 database, {@code SSUAI_DB_PASSWORD}
 * defaults to blank, the JWT secret and credential encryption key fall back to an
 * ephemeral per-JVM random key, and the k8s secretRef is {@code optional: true}.
 * That means a missing/misconfigured secret does NOT crash the app — it boots on
 * H2 or a throwaway key and corrupts data / invalidates everyone's sessions
 * silently. This validator turns "silent insecure fallback" into "refuse to start".
 *
 * <p>Mirrors {@link WebCorsProdConfig}: {@code @Profile("prod")} + constructor-time
 * {@link IllegalStateException} so the failure surfaces during context refresh,
 * before Tomcat binds the port. dev/test keep their H2/ephemeral defaults because
 * this bean simply does not load outside the {@code prod} profile.
 *
 * <p>It validates the SAME values the application actually consumes — the bound
 * {@code @ConfigurationProperties} beans ({@link JwtProperties},
 * {@link LlmChatProperties}, {@link SaintSessionProperties}) plus the resolved
 * {@code spring.datasource.url} and the {@code ssuai.mcp.oauth.*} properties read
 * by {@code McpOAuthSecurityConfig} — rather than re-deriving env-var names, so it
 * cannot drift from real usage.
 */
@Configuration
@Profile("prod")
public class ProdConfigValidator {

    public ProdConfigValidator(
            Environment environment,
            JwtProperties jwtProperties,
            LlmChatProperties llmChatProperties,
            SaintSessionProperties saintSessionProperties
    ) {
        validateDatasourceIsNotH2(environment);
        validateJwtSecret(jwtProperties);
        validateCredentialEncryptionKey(saintSessionProperties);
        validateOauth(environment);
        validateAtLeastOneLlmProviderKey(llmChatProperties);
    }

    /**
     * The resolved {@code spring.datasource.url} must be a real (Postgres) URL,
     * not an H2 in-memory database. When {@code SSUAI_DB_URL} is unset the
     * Environment resolves the {@code jdbc:h2:mem:...} default from
     * application.yml, so this also catches the "secret missing" case.
     */
    private static void validateDatasourceIsNotH2(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Production profile requires spring.datasource.url "
                            + "(env: SSUAI_DB_URL) to be set to a real Postgres URL — it is blank. "
                            + "See docs/security.md.");
        }
        String normalized = url.toLowerCase();
        if (normalized.contains("jdbc:h2") || normalized.contains("h2:mem")) {
            throw new IllegalStateException(
                    "Production profile must not run on H2. spring.datasource.url "
                            + "(env: SSUAI_DB_URL) resolved to an H2/in-memory URL — set a real "
                            + "Postgres JDBC URL. A missing SSUAI_DB_URL silently falls back to H2.");
        }
    }

    /**
     * {@code ssuai.jwt.secret} (env: SSUAI_JWT_SECRET) must be present and
     * non-blank. Blank → {@code JwtProvider} generates an ephemeral random
     * secret, invalidating every issued token on each restart.
     */
    private static void validateJwtSecret(JwtProperties jwtProperties) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Production profile requires ssuai.jwt.secret (env: SSUAI_JWT_SECRET) "
                            + "to be set. Blank falls back to an ephemeral per-JVM key that "
                            + "invalidates all access/refresh tokens on restart.");
        }
    }

    /**
     * {@code ssuai.saint.session.encryption-key} (env: SSUAI_CREDENTIAL_ENCRYPTION_KEY)
     * must be present and non-blank. The SAINT/LMS/library session stores all read
     * this same env var; blank → an ephemeral per-JVM AES key, so every stored school
     * session becomes unreadable after a restart and users must redo SSO.
     */
    private static void validateCredentialEncryptionKey(SaintSessionProperties saintSessionProperties) {
        String key = saintSessionProperties.getEncryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Production profile requires the credential encryption key "
                            + "(env: SSUAI_CREDENTIAL_ENCRYPTION_KEY, "
                            + "ssuai.saint.session.encryption-key) to be set. Blank falls back to "
                            + "an ephemeral per-JVM AES key, making all stored SAINT/LMS/library "
                            + "sessions unreadable after a restart.");
        }
    }

    /**
     * When OAuth 2.1 Resource Server mode is on
     * ({@code ssuai.mcp.oauth.rs-enabled=true}), both {@code issuer-uri} and
     * {@code audience} must be non-blank — otherwise JWT validation cannot be
     * configured and the resource server would accept/reject incorrectly.
     */
    private static void validateOauth(Environment environment) {
        boolean rsEnabled = environment.getProperty("ssuai.mcp.oauth.rs-enabled", Boolean.class, Boolean.FALSE);
        if (!rsEnabled) {
            return;
        }
        String issuerUri = environment.getProperty("ssuai.mcp.oauth.issuer-uri");
        String audience = environment.getProperty("ssuai.mcp.oauth.audience");
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException(
                    "Production profile has ssuai.mcp.oauth.rs-enabled=true but "
                            + "ssuai.mcp.oauth.issuer-uri (env: SSUAI_OAUTH_ISSUER_URI) is blank. "
                            + "OAuth Resource Server mode requires an issuer URI.");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "Production profile has ssuai.mcp.oauth.rs-enabled=true but "
                            + "ssuai.mcp.oauth.audience (env: SSUAI_OAUTH_AUDIENCE) is blank. "
                            + "OAuth Resource Server mode requires an audience claim value.");
        }
    }

    /**
     * At least one LLM chat provider must have a non-blank API key. The chat path
     * has no use without a working upstream; a fully blank set means the deploy
     * forgot every provider key. Iterates the bound provider beans (rather than
     * re-listing env-var names) so it cannot drift from {@link LlmChatProperties}.
     */
    private static void validateAtLeastOneLlmProviderKey(LlmChatProperties llmChatProperties) {
        Map<String, DirectProvider> providers = new LinkedHashMap<>();
        providers.put("gemini", llmChatProperties.getGemini());
        providers.put("groq", llmChatProperties.getGroq());
        providers.put("cerebras", llmChatProperties.getCerebras());
        providers.put("deepinfra", llmChatProperties.getDeepinfra());
        providers.put("sambanova", llmChatProperties.getSambanova());
        providers.put("nscale", llmChatProperties.getNscale());
        providers.put("fireworks", llmChatProperties.getFireworks());
        providers.put("huggingface", llmChatProperties.getHuggingface());
        providers.put("mistral", llmChatProperties.getMistral());
        providers.put("openrouter", llmChatProperties.getOpenrouter());

        boolean anyConfigured = providers.values().stream()
                .anyMatch(p -> p != null && p.getApiKey() != null && !p.getApiKey().isBlank());
        if (!anyConfigured) {
            throw new IllegalStateException(
                    "Production profile requires at least one LLM provider API key "
                            + "(e.g. SSUAI_GEMINI_API_KEY / SSUAI_GROQ_API_KEY / "
                            + "SSUAI_OPENROUTER_API_KEY / SSUAI_CEREBRAS_API_KEY / ...). "
                            + "All of ssuai.chat.llm.{" + String.join(", ", List.copyOf(providers.keySet()))
                            + "}.api-key are blank.");
        }
    }
}
