package com.ssuai.global.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for {@link GlobalLlmSpendBreaker} (SCALE-ROADMAP Phase 1 audit A3 —
 * {@code ssuai.resilience.llm-spend.*}).
 *
 * <p>Per-IP request caps ({@code ssuai.ratelimit.chat-per-minute}, ADR 0061) bound
 * how fast ONE caller can hit metered LLM endpoints, but do nothing to stop MANY
 * distinct IPs — more real users, or one attacker rotating source IPs — from
 * driving an unbounded bill. This property set carries two independent Redis-shared
 * call-count budgets (daily / monthly) per metered surface ("meter"):
 *
 * <ul>
 *   <li>{@code chat} — {@code LlmProviderChain}, covering every chat completion
 *       provider (Gemini direct + the free-tier OpenAI-compatible providers +
 *       OpenRouter/Mistral) behind one combined call-count budget.</li>
 *   <li>{@code embedding} — {@code AcademicEmbeddingClient}'s Gemini
 *       {@code gemini-embedding-001} calls (academic-policy hybrid RAG).</li>
 * </ul>
 *
 * <p>Defaults are sized generously against today's real traffic (SCALE-ROADMAP:
 * "실사용 수십명" — dozens of real users) so the breaker never trips under normal
 * load; see each field's javadoc and ADR 0081 for the numbers.
 */
@Component
@ConfigurationProperties(prefix = "ssuai.resilience.llm-spend")
public class GlobalLlmSpendProperties {

    private boolean redisEnabled = true;

    /** Fraction of a ceiling at which {@link GlobalLlmSpendBreaker} logs a WARN. */
    private double warnThresholdRatio = 0.8;

    /**
     * chatPerMinute (ADR 0061) is 30/IP; at dozens of concurrent real users that
     * is at most a few thousand chat completions per day in practice (each chat
     * turn is usually 1-2 {@code LlmProviderChain.complete()} calls including
     * provider fallback). 5,000/day and 100,000/month sit far above that so
     * today's traffic never trips the breaker, while still capping a many-IP
     * cost-runaway scenario at a bounded, known daily/monthly ceiling.
     */
    private Meter chat = new Meter(5_000, 100_000);

    /**
     * Gemini's {@code gemini-embedding-001} free tier caps at 1,000 requests/day
     * (SCALE-ROADMAP audit H1). 800/day sits comfortably below that so THIS
     * breaker trips first with a controlled, observable degrade-to-lexical
     * (same path as any other embedding failure) instead of a raw Gemini 429
     * surfacing mid-request. 20,000/month gives room for ~25 days of a fully
     * warmed daily ceiling without being a second, tighter monthly trap.
     */
    private Meter embedding = new Meter(800, 20_000);

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public double getWarnThresholdRatio() {
        return warnThresholdRatio;
    }

    public void setWarnThresholdRatio(double warnThresholdRatio) {
        if (warnThresholdRatio <= 0 || warnThresholdRatio > 1) {
            throw new IllegalArgumentException("warnThresholdRatio must be in (0, 1]");
        }
        this.warnThresholdRatio = warnThresholdRatio;
    }

    public Meter getChat() {
        return chat;
    }

    public void setChat(Meter chat) {
        this.chat = chat;
    }

    public Meter getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Meter embedding) {
        this.embedding = embedding;
    }

    /** Resolves the {@link Meter} config for a meter name ("chat" / "embedding"). */
    public Meter meterFor(String name) {
        return switch (name) {
            case "chat" -> chat;
            case "embedding" -> embedding;
            default -> throw new IllegalArgumentException("Unknown LLM spend meter: " + name);
        };
    }

    public static class Meter {

        private long dailyCallCeiling;
        private long monthlyCallCeiling;

        public Meter() {
        }

        public Meter(long dailyCallCeiling, long monthlyCallCeiling) {
            this.dailyCallCeiling = requirePositive(dailyCallCeiling, "dailyCallCeiling");
            this.monthlyCallCeiling = requirePositive(monthlyCallCeiling, "monthlyCallCeiling");
        }

        public long getDailyCallCeiling() {
            return dailyCallCeiling;
        }

        public void setDailyCallCeiling(long dailyCallCeiling) {
            this.dailyCallCeiling = requirePositive(dailyCallCeiling, "dailyCallCeiling");
        }

        public long getMonthlyCallCeiling() {
            return monthlyCallCeiling;
        }

        public void setMonthlyCallCeiling(long monthlyCallCeiling) {
            this.monthlyCallCeiling = requirePositive(monthlyCallCeiling, "monthlyCallCeiling");
        }

        private static long requirePositive(long value, String field) {
            if (value < 1) {
                throw new IllegalArgumentException(field + " must be >= 1");
            }
            return value;
        }
    }
}
