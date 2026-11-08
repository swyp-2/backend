package com.baton.auth.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.baton.auth.domain.SocialProvider;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.config.AuthProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Google·Apple 형식으로 직접 서명한 ID 토큰과 테스트 JWKS로 실제 검증기를 확인한다.
 */
class JwksSocialTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-07T01:00:00Z");
    private static final String GOOGLE_JWKS = "https://google.test/oauth2/v3/certs";
    private static final String APPLE_JWKS = "https://apple.test/auth/keys";
    private static final String GOOGLE_WEB_CLIENT = "google-web-client";
    private static final String APPLE_BUNDLE = "com.baton.app";

    private static RSAKey googleKey;
    private static RSAKey appleKey;
    private static RSAKey attackerKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate)
            .ignoreExpectOrder(true).build();
    private final JwksSocialTokenVerifier verifier = new JwksSocialTokenVerifier(
            new AuthProperties("unit-test-secret-0123456789abcdef0123", Duration.ofMinutes(30), Duration.ofDays(14),
                    new AuthProperties.Provider(List.of("google-ios-client", GOOGLE_WEB_CLIENT), GOOGLE_JWKS),
                    new AuthProperties.Provider(List.of(APPLE_BUNDLE), APPLE_JWKS)),
            restTemplate, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeAll
    static void generateKeys() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-kid").generate();
        appleKey = new RSAKeyGenerator(2048).keyID("apple-kid").generate();
        // 공격자가 같은 kid로 자기 키를 써서 서명한 경우
        attackerKey = new RSAKeyGenerator(2048).keyID("google-kid").generate();
    }

    @Test
    void Google_두_가지_iss_형식을_모두_통과시키고_sub를_돌려준다() throws Exception {
        serveJwks();

        assertThat(verifier.verify(SocialProvider.GOOGLE, google(c -> c.issuer("https://accounts.google.com"))))
                .isEqualTo(new VerifiedSocialIdentity(SocialProvider.GOOGLE, "google-sub-1"));
        assertThat(verifier.verify(SocialProvider.GOOGLE, google(c -> c.issuer("accounts.google.com"))).subject())
                .isEqualTo("google-sub-1");
    }

    @Test
    void Apple_토큰을_통과시킨다() throws Exception {
        serveJwks();

        assertThat(verifier.verify(SocialProvider.APPLE, apple(c -> { })))
                .isEqualTo(new VerifiedSocialIdentity(SocialProvider.APPLE, "apple-sub-1"));
    }

    @Test
    void iss가_다르면_거부한다() throws Exception {
        serveJwks();

        assertUnauthenticated(SocialProvider.GOOGLE, google(c -> c.issuer("https://evil.example.com")));
        assertUnauthenticated(SocialProvider.APPLE, apple(c -> c.issuer("https://accounts.google.com")));
    }

    @Test
    void aud가_설정된_client_id가_아니면_거부한다() throws Exception {
        serveJwks();

        assertUnauthenticated(SocialProvider.GOOGLE, google(c -> c.audience("someone-elses-client")));
        assertUnauthenticated(SocialProvider.APPLE, apple(c -> c.audience("com.other.app")));
    }

    @Test
    void exp가_지났으면_거부한다() throws Exception {
        serveJwks();

        assertUnauthenticated(SocialProvider.GOOGLE, google(c -> c.expirationTime(Date.from(NOW.minusSeconds(120)))));
        assertUnauthenticated(SocialProvider.APPLE, apple(c -> c.expirationTime(Date.from(NOW.minusSeconds(120)))));
    }

    @Test
    void 다른_키로_서명했거나_sub가_없으면_거부한다() throws Exception {
        serveJwks();

        assertUnauthenticated(SocialProvider.GOOGLE, sign(attackerKey, googleClaims(c -> { })));
        assertUnauthenticated(SocialProvider.GOOGLE, google(c -> c.subject(null)));
        assertUnauthenticated(SocialProvider.GOOGLE, "not-a-jwt");
    }

    @Test
    void 다른_제공자의_토큰은_거부한다() throws Exception {
        serveJwks();

        assertUnauthenticated(SocialProvider.APPLE, google(c -> { }));
    }

    @Test
    void 제공자_키_조회_실패는_재시도_가능한_서버_오류다() throws Exception {
        server.expect(ExpectedCount.manyTimes(), requestTo(GOOGLE_JWKS)).andRespond(withServerError());

        assertThatThrownBy(() -> verifier.verify(SocialProvider.GOOGLE, google(c -> { })))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private void serveJwks() {
        server.expect(ExpectedCount.manyTimes(), requestTo(GOOGLE_JWKS))
                .andRespond(withSuccess(new JWKSet(googleKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(APPLE_JWKS))
                .andRespond(withSuccess(new JWKSet(appleKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
    }

    private void assertUnauthenticated(SocialProvider provider, String idToken) {
        assertThatThrownBy(() -> verifier.verify(provider, idToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    private static String google(Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        return sign(googleKey, googleClaims(customizer));
    }

    private static JWTClaimsSet googleClaims(Consumer<JWTClaimsSet.Builder> customizer) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(GOOGLE_WEB_CLIENT)
                .subject("google-sub-1")
                .claim("azp", GOOGLE_WEB_CLIENT)
                .claim("email", "user@example.com")
                .claim("email_verified", true)
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(3600)));
        customizer.accept(builder);
        return builder.build();
    }

    private static String apple(Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://appleid.apple.com")
                .audience(APPLE_BUNDLE)
                .subject("apple-sub-1")
                .claim("email", "relay@privaterelay.appleid.com")
                .claim("is_private_email", "true")
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(600)));
        customizer.accept(builder);
        return sign(appleKey, builder.build());
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
