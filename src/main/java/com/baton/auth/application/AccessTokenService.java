package com.baton.auth.application;

import com.baton.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 자체 액세스 토큰(HS256). claim은 sub(user_id), sid(auth_sessions.id)와 수명 표시용 iat·exp만 둔다.
 */
@Component
public class AccessTokenService {

    public static final String SESSION_ID_CLAIM = "sid";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final Duration ttl;

    public AccessTokenService(AuthProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        this.decoder = buildDecoder(key, clock);
        this.clock = clock;
        this.ttl = properties.accessTokenTtl();
    }

    public String issue(long userId, long sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim(SESSION_ID_CLAIM, sessionId)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /** 요청 인증 필터가 쓰는 디코더. 서명(HS256 고정)·만료·sub/sid 형식을 검증한다. */
    public JwtDecoder decoder() {
        return decoder;
    }

    private static JwtDecoder buildDecoder(SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 같은 서버가 발급·검증하므로 시계 오차 허용을 두지 않는다
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, AccessTokenService::validateClaims));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        boolean subjectIsId = jwt.getSubject() != null && jwt.getSubject().matches("\\d{1,18}");
        boolean sessionIsId = jwt.getClaims().get(SESSION_ID_CLAIM) instanceof Number;
        if (subjectIsId && sessionIsId) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub/sid 형식 오류", null));
    }
}
