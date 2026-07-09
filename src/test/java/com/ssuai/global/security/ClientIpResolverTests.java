package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SCALE-ROADMAP Phase 1 audit A2: {@link ClientIpResolver} must resolve the
 * real client from a <em>trusted</em> position in {@code X-Forwarded-For} —
 * counted from the right (our own infrastructure's append operations), never
 * the left-most entry (fully attacker-controlled).
 */
class ClientIpResolverTests {

    private static MockHttpServletRequest request(String xff, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader(ClientIpResolver.X_FORWARDED_FOR, xff);
        }
        return request;
    }

    @Test
    void noHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = request(null, "10.0.0.1");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("10.0.0.1");
    }

    @Test
    void singleEntryHeaderIsUsedAndTrimmedWithTrustedHopOne() {
        MockHttpServletRequest request = request("  198.51.100.5  ", "10.0.0.1");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("198.51.100.5");
    }

    @Test
    void forgedLeftMostEntryIsIgnoredWithTrustedHopOne() {
        // Attacker sets XFF: "9.9.9.9" trying to rotate its own bucket. Traefik
        // (the one trusted hop) appends the real peer address as it forwards.
        MockHttpServletRequest request = request("9.9.9.9, 203.0.113.7", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("203.0.113.7");
    }

    @Test
    void anyNumberOfForgedPrefixEntriesIsIgnoredWithTrustedHopOne() {
        // The attacker can prepend as many fake hops as it likes — only the
        // right-most (trusted) entry is ever used.
        MockHttpServletRequest request =
                request("1.1.1.1, 2.2.2.2, 3.3.3.3, 4.4.4.4, 203.0.113.7", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedHopTwoPicksSecondFromRightForVercelPlusTraefikChain() {
        // client -> Vercel (appends real client) -> Traefik (appends Vercel's IP).
        // Header arriving at backend: "<forged>, real-client, vercel-ip".
        MockHttpServletRequest request = request("9.9.9.9, 203.0.113.7, 198.51.100.9", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 2)).isEqualTo("203.0.113.7");
    }

    @Test
    void fewerHopsThanTrustedProxyCountFallsBackToRemoteAddr() {
        // trustedProxyCount=2 expects at least 2 entries; only 1 present.
        MockHttpServletRequest request = request("203.0.113.7", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 2)).isEqualTo("10.0.0.2");
    }

    @Test
    void trustedProxyCountZeroAlwaysUsesRemoteAddrEvenWithHeaderPresent() {
        MockHttpServletRequest request = request("203.0.113.7, 198.51.100.9", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 0)).isEqualTo("10.0.0.2");
    }

    @Test
    void blankHeaderFallsBackToRemoteAddr() {
        MockHttpServletRequest request = request("   ", "10.0.0.1");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("10.0.0.1");
    }

    @Test
    void blankEntryAtTrustedPositionFallsBackToRemoteAddr() {
        // Malformed header: trailing comma leaves a blank right-most entry.
        MockHttpServletRequest request = request("203.0.113.7, ", "10.0.0.2");
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("10.0.0.2");
    }

    @Test
    void bothSourcesMissingReturnsUnknown() {
        MockHttpServletRequest request = request(null, null);
        assertThat(ClientIpResolver.resolve(request, 1)).isEqualTo("unknown");
    }
}
