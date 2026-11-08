package com.baton.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class AccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T01:00:00Z");
    private static final String SECRET = "unit-test-secret-0123456789abcdef0123";

    private final AccessTokenService service = serviceAt(NOW, SECRET);

    @Test
    void claim은_sub_sid_iat_exp만_담고_수명은_14일이다() {
        Jwt jwt = service.decoder().decode(service.issue(7L, 42L));

        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "sid", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(((Number) jwt.getClaims().get("sid")).longValue()).isEqualTo(42L);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(service.ttlSeconds()).isEqualTo(14 * 24 * 60 * 60);
    }

    @Test
    void 만료_직전에는_통과하고_만료_시각이_지나면_거부한다() {
        String token = service.issue(7L, 42L);
        AccessTokenService justBefore = serviceAt(NOW.plus(Duration.ofDays(14)).minusSeconds(1), SECRET);
        AccessTokenService after = serviceAt(NOW.plus(Duration.ofDays(14)).plusSeconds(1), SECRET);

        assertThat(justBefore.decoder().decode(token).getSubject()).isEqualTo("7");
        assertThatThrownBy(() -> after.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_거부한다() {
        String forged = serviceAt(NOW, "another-secret-0123456789abcdef01234567").issue(7L, 42L);

        assertThatThrownBy(() -> service.decoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void 서명_없는_alg_none_토큰은_거부한다() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"7\",\"sid\":42,\"exp\":" + NOW.plusSeconds(600).getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.decoder().decode(header + "." + payload + "."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void 짧은_서명_키는_설정_단계에서_거부한다() {
        assertThatThrownBy(() -> new AuthProperties("too-short", Duration.ofDays(14), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AccessTokenService serviceAt(Instant now, String secret) {
        AuthProperties properties = new AuthProperties(secret, Duration.ofDays(14), null, null);
        return new AccessTokenService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }
}
