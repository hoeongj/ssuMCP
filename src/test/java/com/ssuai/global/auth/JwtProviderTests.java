package com.ssuai.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.user.entity.Student;

class JwtProviderTests {

    private static final String BASE64_SECRET =
            Base64.getEncoder().encodeToString(
                    "test-only-secret-with-at-least-32-bytes-aaaa".getBytes());
    private static final Instant NOW = Instant.parse("2026-05-16T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void issueAccessRoundTrips() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);

        String token = provider.issueAccess(student);
        JwtClaims claims = provider.parse(token, JwtTokenType.ACCESS);

        assertThat(claims.studentId()).isEqualTo("20210001");
        assertThat(claims.name()).isEqualTo("홍길동");
        assertThat(claims.type()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(claims.jti()).isNull();
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void issueRefreshRoundTrips() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);

        String token = provider.issueRefresh(student);
        JwtClaims claims = provider.parse(token, JwtTokenType.REFRESH);

        assertThat(claims.type()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(claims.jti()).isNotBlank();
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void issueRefreshGeneratesUniqueJti() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "student", "major", "active", NOW);

        JwtClaims first = provider.parse(provider.issueRefresh(student), JwtTokenType.REFRESH);
        JwtClaims second = provider.parse(provider.issueRefresh(student), JwtTokenType.REFRESH);

        assertThat(first.jti()).isNotBlank();
        assertThat(second.jti()).isNotBlank();
        assertThat(first.jti()).isNotEqualTo(second.jti());
    }

    @Test
    void refreshTokenWithoutJtiIsRejected() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(BASE64_SECRET));
        String token = Jwts.builder()
                .issuer("ssuai-test")
                .subject("20210001")
                .claim(JwtProvider.CLAIM_NAME, "student")
                .claim(JwtProvider.CLAIM_TYPE, JwtTokenType.REFRESH.name())
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(Duration.ofDays(14))))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> provider.parse(token, JwtTokenType.REFRESH))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("missing refresh jti");
    }

    @Test
    void typeMismatchIsRejected() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        String access = provider.issueAccess(student);

        assertThatThrownBy(() -> provider.parse(access, JwtTokenType.REFRESH))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("type mismatch");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtProvider issuer = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        String token = issuer.issueAccess(student);

        Clock later = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        JwtProvider verifier = new JwtProvider(properties(), later);

        assertThatThrownBy(() -> verifier.parse(token, JwtTokenType.ACCESS))
                .isInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtProvider provider = new JwtProvider(properties(), FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        String token = provider.issueAccess(student);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThatThrownBy(() -> provider.parse(tampered, JwtTokenType.ACCESS))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void tokenSignedByDifferentSecretIsRejected() {
        JwtProperties otherProps = properties();
        otherProps.setSecret(Base64.getEncoder().encodeToString(
                "completely-different-secret-with-32-bytes-aaaa".getBytes()));
        JwtProvider otherIssuer = new JwtProvider(otherProps, FIXED_CLOCK);
        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        String foreign = otherIssuer.issueAccess(student);

        JwtProvider verifier = new JwtProvider(properties(), FIXED_CLOCK);
        assertThatThrownBy(() -> verifier.parse(foreign, JwtTokenType.ACCESS))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void shortSecretIsRejected() {
        JwtProperties tooShort = properties();
        tooShort.setSecret("dGVzdA=="); // base64 of "test" = 4 bytes

        assertThatThrownBy(() -> new JwtProvider(tooShort, FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void secretWithTrailingCarriageReturnDerivesTheSameKey() {
        // A trailing \r (Windows env-var artifact) must not change the derived signing key.
        // Before the .strip() hardening it broke base64 decode -> raw-bytes fallback -> a
        // different key, so a token issued with the clean secret failed to parse.
        JwtProvider clean = new JwtProvider(properties(), FIXED_CLOCK);
        JwtProperties withCr = properties();
        withCr.setSecret(BASE64_SECRET + "\r");
        JwtProvider crProvider = new JwtProvider(withCr, FIXED_CLOCK);

        Student student = new Student("20210001", "홍길동", "컴퓨터학부", "재학", NOW);
        String token = clean.issueAccess(student);

        JwtClaims claims = crProvider.parse(token, JwtTokenType.ACCESS);
        assertThat(claims.studentId()).isEqualTo("20210001");
        assertThat(claims.type()).isEqualTo(JwtTokenType.ACCESS);
    }

    private static JwtProperties properties() {
        JwtProperties props = new JwtProperties();
        props.setSecret(BASE64_SECRET);
        props.setIssuer("ssuai-test");
        return props;
    }
}
