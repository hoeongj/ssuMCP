package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.ssuai.domain.auth.saint.SaintSessionProperties;
import com.ssuai.domain.chat.config.LlmChatProperties;
import com.ssuai.global.auth.JwtProperties;

/**
 * Direct unit tests for {@link ProdConfigValidator}. Constructs the validator
 * with a {@link MockEnvironment} and stub property beans — no Spring context
 * boot — so each required value can be flipped to the missing/insecure case
 * independently.
 */
class ProdConfigValidatorTest {

    private static final String REAL_PG_URL = "jdbc:postgresql://postgres-service:5432/ssuai";
    private static final String VALID_JWT_SECRET = "a-32-byte-or-longer-jwt-secret-value-here";
    private static final String VALID_ENC_KEY = "a-32-byte-or-longer-aes-encryption-key";

    private MockEnvironment validEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", REAL_PG_URL);
        env.setProperty("ssuai.mcp.oauth.rs-enabled", "false");
        return env;
    }

    private JwtProperties validJwtProperties() {
        JwtProperties props = new JwtProperties();
        props.setSecret(VALID_JWT_SECRET);
        return props;
    }

    private SaintSessionProperties validSaintSessionProperties() {
        SaintSessionProperties props = new SaintSessionProperties();
        props.setEncryptionKey(VALID_ENC_KEY);
        return props;
    }

    private LlmChatProperties llmWithGeminiKey() {
        LlmChatProperties props = new LlmChatProperties();
        props.getGemini().setApiKey("gemini-key");
        return props;
    }

    private LlmChatProperties llmWithNoKeys() {
        // Fresh instance: every provider's apiKey defaults to "".
        return new LlmChatProperties();
    }

    private void construct(
            MockEnvironment env,
            JwtProperties jwt,
            LlmChatProperties llm,
            SaintSessionProperties saint
    ) {
        new ProdConfigValidator(env, jwt, llm, saint);
    }

    @Test
    void passesWithCompleteProdLikeConfig() {
        assertThatCode(() -> construct(
                validEnvironment(),
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWhenOauthEnabledWithIssuerAndAudience() {
        MockEnvironment env = validEnvironment();
        env.setProperty("ssuai.mcp.oauth.rs-enabled", "true");
        env.setProperty("ssuai.mcp.oauth.issuer-uri", "https://issuer.example.com");
        env.setProperty("ssuai.mcp.oauth.audience", "https://ssumcp.duckdns.org");

        assertThatCode(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    void throwsWhenDatasourceIsH2InMemory() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:ssuai;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");

        assertThatThrownBy(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url");
    }

    @Test
    void throwsWhenDatasourceUrlBlank() {
        MockEnvironment env = validEnvironment();
        env.setProperty("spring.datasource.url", "   ");

        assertThatThrownBy(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url");
    }

    @Test
    void throwsWhenJwtSecretBlank() {
        JwtProperties jwt = new JwtProperties();
        jwt.setSecret("   ");

        assertThatThrownBy(() -> construct(
                validEnvironment(),
                jwt,
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ssuai.jwt.secret");
    }

    @Test
    void throwsWhenCredentialEncryptionKeyBlank() {
        SaintSessionProperties saint = new SaintSessionProperties();
        saint.setEncryptionKey("");

        assertThatThrownBy(() -> construct(
                validEnvironment(),
                validJwtProperties(),
                llmWithGeminiKey(),
                saint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSUAI_CREDENTIAL_ENCRYPTION_KEY");
    }

    @Test
    void throwsWhenOauthEnabledButIssuerBlank() {
        MockEnvironment env = validEnvironment();
        env.setProperty("ssuai.mcp.oauth.rs-enabled", "true");
        env.setProperty("ssuai.mcp.oauth.issuer-uri", "");
        env.setProperty("ssuai.mcp.oauth.audience", "https://ssumcp.duckdns.org");

        assertThatThrownBy(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");
    }

    @Test
    void throwsWhenOauthEnabledButAudienceBlank() {
        MockEnvironment env = validEnvironment();
        env.setProperty("ssuai.mcp.oauth.rs-enabled", "true");
        env.setProperty("ssuai.mcp.oauth.issuer-uri", "https://issuer.example.com");
        env.setProperty("ssuai.mcp.oauth.audience", "   ");

        assertThatThrownBy(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void throwsWhenNoLlmProviderKeyConfigured() {
        assertThatThrownBy(() -> construct(
                validEnvironment(),
                validJwtProperties(),
                llmWithNoKeys(),
                validSaintSessionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM provider API key");
    }

    @Test
    void passesWhenOnlyANonFirstLlmProviderKeyConfigured() {
        LlmChatProperties llm = new LlmChatProperties();
        // Only openrouter has a key; gemini (first in order) is blank.
        llm.getOpenrouter().setApiKey("openrouter-key");

        assertThatCode(() -> construct(
                validEnvironment(),
                validJwtProperties(),
                llm,
                validSaintSessionProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    void oauthDisabledDoesNotRequireIssuerOrAudience() {
        MockEnvironment env = validEnvironment();
        // rs-enabled defaults false; issuer/audience absent — must still pass.
        assertThatCode(() -> construct(
                env,
                validJwtProperties(),
                llmWithGeminiKey(),
                validSaintSessionProperties()))
                .doesNotThrowAnyException();

        // Sanity: the provider order constant is unaffected by validation.
        assertThatCode(() -> List.copyOf(llmWithGeminiKey().getProviderOrder()))
                .doesNotThrowAnyException();
    }
}
