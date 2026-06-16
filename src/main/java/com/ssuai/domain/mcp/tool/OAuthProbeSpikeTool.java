package com.ssuai.domain.mcp.tool;

import java.time.Instant;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * SPIKE TOOL — throwaway. Remove in follow-up PR after OAuth round-trip smoke test.
 *
 * <p>Verifies that the OAuth subject ({@code sub} claim) is stable across turn
 * boundaries — the property the whole EPIC depends on.
 *
 * <h2>How to use for the smoke test</h2>
 * <ol>
 *   <li>Connect ChatGPT (the actual failing client) to the prod HTTPS MCP URL
 *       ({@code https://ssumcp.duckdns.org/mcp}) with {@code SSUAI_OAUTH_RS_ENABLED=true}.</li>
 *   <li>Complete the OAuth flow (ChatGPT will auto-trigger via DCR + PKCE).</li>
 *   <li>Call this tool once: record the {@code sub=...} value.</li>
 *   <li>Ask ChatGPT a completely unrelated question (forces a turn boundary).</li>
 *   <li>Call this tool again: compare both {@code sub} values.</li>
 *   <li>PASS: both {@code sub} values are identical → OAuth identity is stable →
 *       proceed to Phase 1. FAIL: values differ → investigate connector behavior.</li>
 * </ol>
 *
 * <p>{@code @Profile("!test")} prevents the tool from registering in the test profile,
 * so {@code McpServerConfigTests} and {@code McpSelfDogfoodTests} (which assert an
 * exact tool list) continue to pass without modification.
 */
@Component
@Profile("!test")
public class OAuthProbeSpikeTool {

    @Tool(description = "SPIKE: Returns the OAuth JWT subject (sub claim) from the Bearer token. "
            + "Call twice in separate turns and compare sub values — if they match, "
            + "OAuth identity is stable across turn boundaries and the full implementation proceeds. "
            + "Delete after OAuth smoke test.")
    public String check_oauth_sub_stability() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null) {
            return "ERROR: SecurityContext has no Authentication — "
                    + "is SSUAI_OAUTH_RS_ENABLED=true and a valid Bearer token present?";
        }
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return "ERROR: Expected JwtAuthenticationToken but got "
                    + auth.getClass().getSimpleName()
                    + " — OAuth RS may not be active";
        }

        String sub = jwtAuth.getToken().getSubject();
        String issuer = String.valueOf(jwtAuth.getToken().getIssuer());
        String scopes = jwtAuth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .reduce((a, b) -> a + "," + b)
                .orElse("(none)");

        return String.format(
                "sub=%s | issuer=%s | scopes=[%s] | timestamp=%s",
                sub, issuer, scopes, Instant.now());
    }
}
