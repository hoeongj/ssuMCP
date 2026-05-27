package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class McpAuthSessionIdTests {

    @Test
    void generateProducesNonBlankValue() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        assertThat(id.value()).isNotBlank();
    }

    @Test
    void generateProducesUniqueValues() {
        Set<String> values = IntStream.range(0, 100)
                .mapToObj(i -> McpAuthSessionId.generate().value())
                .collect(Collectors.toSet());
        assertThat(values).hasSize(100);
    }

    @Test
    void fingerprintIsEightCharHex() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        String fp = id.fingerprint();
        assertThat(fp).hasSize(8);
        assertThat(fp).matches("[0-9a-f]{8}");
    }

    @Test
    void fingerprintIsStableForSameValue() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        assertThat(id.fingerprint()).isEqualTo(id.fingerprint());
    }

    @Test
    void differentIdsProduceDifferentFingerprints() {
        McpAuthSessionId a = McpAuthSessionId.generate();
        McpAuthSessionId b = McpAuthSessionId.generate();
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void equalityBasedOnValue() {
        McpAuthSessionId a = new McpAuthSessionId("same");
        McpAuthSessionId b = new McpAuthSessionId("same");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void constructorRejectsBlankValue() {
        assertThatThrownBy(() -> new McpAuthSessionId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new McpAuthSessionId(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpAuthSessionId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringContainsFingerprintNotRawValue() {
        McpAuthSessionId id = McpAuthSessionId.generate();
        String str = id.toString();
        assertThat(str).contains(id.fingerprint());
        assertThat(str).doesNotContain(id.value());
    }
}
