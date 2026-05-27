package com.ssuai.global.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.domain.user.entity.Student;

@Component
public class JwtProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtProvider.class);

    static final String CLAIM_NAME = "name";
    static final String CLAIM_TYPE = "typ";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;

    @Autowired
    public JwtProvider(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = buildSigningKey(properties.getSecret());
    }

    public String issueAccess(Student student) {
        return issue(student, JwtTokenType.ACCESS, properties.getAccessTtl());
    }

    public String issueRefresh(Student student) {
        return issue(student, JwtTokenType.REFRESH, properties.getRefreshTtl());
    }

    public JwtClaims parse(String token, JwtTokenType expectedType) {
        Jws<Claims> parsed;
        try {
            parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException exception) {
            throw new InvalidJwtException("expired", exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidJwtException("invalid", exception);
        }

        Claims claims = parsed.getPayload();
        JwtTokenType actualType = parseType(claims.get(CLAIM_TYPE));
        if (actualType != expectedType) {
            throw new InvalidJwtException(
                    "token type mismatch: expected=" + expectedType + " actual=" + actualType);
        }

        return new JwtClaims(
                claims.getSubject(),
                claims.get(CLAIM_NAME, String.class),
                actualType,
                claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()
        );
    }

    private String issue(Student student, JwtTokenType type, java.time.Duration ttl) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(student.getStudentId())
                .claim(CLAIM_NAME, student.getName())
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    private static SecretKey buildSigningKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("ssuai.jwt.secret is empty — generated an ephemeral random secret. "
                    + "All issued tokens will be invalid after restart. "
                    + "Set SSUAI_JWT_SECRET for any non-dev environment.");
            return Keys.hmacShaKeyFor(random);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException ignored) {
            // Allow plain (non-base64) secret as a dev convenience.
            decoded = base64Secret.getBytes(StandardCharsets.UTF_8);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "ssuai.jwt.secret must decode to at least 32 bytes for HS256");
        }
        return Keys.hmacShaKeyFor(decoded);
    }

    private static JwtTokenType parseType(Object raw) {
        if (raw instanceof String text) {
            try {
                return JwtTokenType.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
