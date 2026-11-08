package com.baton.auth.application.social;

import com.baton.auth.domain.SocialProvider;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.config.AuthProperties;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;

/**
 * Google·Apple ID 토큰을 제공자 JWKS(RS256)로 검증하고 iss·aud·exp·sub를 확인한다.
 * JWKS는 첫 검증 때 조회하고 캐시한다.
 */
public class JwksSocialTokenVerifier implements SocialTokenVerifier {

    static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    static final Set<String> APPLE_ISSUERS = Set.of("https://appleid.apple.com");
    private static final int MAX_SUBJECT_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(JwksSocialTokenVerifier.class);

    private static final OAuth2TokenValidator<Jwt> SUBJECT = jwt -> {
        String subject = jwt.getSubject();
        return check(subject != null && !subject.isBlank() && subject.length() <= MAX_SUBJECT_LENGTH, "sub 없음");
    };

    private final Map<SocialProvider, JwtDecoder> decoders = new EnumMap<>(SocialProvider.class);

    public JwksSocialTokenVerifier(AuthProperties properties, RestOperations restOperations, Clock clock) {
        register(SocialProvider.GOOGLE, properties.google(), GOOGLE_ISSUERS, restOperations, clock);
        register(SocialProvider.APPLE, properties.apple(), APPLE_ISSUERS, restOperations, clock);
    }

    @Override
    public VerifiedSocialIdentity verify(SocialProvider provider, String idToken) {
        JwtDecoder decoder = decoders.get(provider);
        if (decoder == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return new VerifiedSocialIdentity(provider, decoder.decode(idToken).getSubject());
        } catch (BadJwtException e) {
            // 서명·형식·iss·aud·exp 불일치 (JwtValidationException 포함)
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        } catch (JwtException e) {
            log.warn("{} JWKS 조회 실패", provider, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void register(SocialProvider provider, AuthProperties.Provider config, Set<String> issuers,
                          RestOperations restOperations, Clock clock) {
        if (config.jwkSetUri() == null || config.jwkSetUri().isBlank()) {
            return;
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(config.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(restOperations)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                issuer(issuers),
                audience(config.clientIds()),
                SUBJECT));
        decoders.put(provider, decoder);
    }

    private static OAuth2TokenValidator<Jwt> issuer(Set<String> issuers) {
        return jwt -> check(issuers.contains(jwt.getClaimAsString("iss")), "iss 불일치");
    }

    private static OAuth2TokenValidator<Jwt> audience(List<String> clientIds) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return check(audience != null && audience.stream().anyMatch(clientIds::contains), "aud 불일치");
        };
    }

    private static OAuth2TokenValidatorResult check(boolean valid, String description) {
        return valid
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
