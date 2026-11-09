package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.config.ShiftBatchProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PreviewTokenCodecTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final String SECRET = "unit-test-preview-secret-0123456789abcdef";
    private static final PreviewTokenCodec.Claims CLAIMS =
            new PreviewTokenCodec.Claims(7L, 11L, "a".repeat(64), "b".repeat(64));

    private final PreviewTokenCodec issuer = codec(SECRET, NOW);

    @Test
    void 발급한_토큰은_30분_경계까지_그대로_검증된다() {
        String token = issuer.issue(CLAIMS);

        assertThat(codec(SECRET, NOW).verify(token)).isEqualTo(CLAIMS);
        assertThat(codec(SECRET, NOW.plus(Duration.ofMinutes(30))).verify(token)).isEqualTo(CLAIMS);
    }

    @Test
    void 만료_31분_뒤에는_거부() {
        String token = issuer.issue(CLAIMS);

        assertStale(codec(SECRET, NOW.plus(Duration.ofMinutes(31))), token);
    }

    @Test
    void 서명_한_글자만_바꿔도_거부() {
        String token = issuer.issue(CLAIMS);
        int dot = token.indexOf('.');
        // 마지막 글자(남는 비트 포함)와 서명 첫 글자 모두 확인
        assertStale(issuer, flip(token, token.length() - 1));
        assertStale(issuer, flip(token, dot + 1));
    }

    @Test
    void payload를_바꿔치기하면_거부() {
        String token = issuer.issue(CLAIMS);
        String signature = token.substring(token.indexOf('.') + 1);
        String forged = "v1|7|99|" + NOW.plusSeconds(1800).getEpochSecond() + "|" + "a".repeat(64) + "|"
                + "b".repeat(64);
        String forgedPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8));

        assertStale(issuer, forgedPart + "." + signature);
        assertStale(issuer, flip(token, 0));
    }

    @Test
    void 다른_키로_서명한_토큰은_거부() {
        String token = codec("another-preview-secret-0123456789abcdef", NOW).issue(CLAIMS);

        assertStale(issuer, token);
    }

    @Test
    void 형식이_틀리면_거부() {
        assertStale(issuer, "");
        assertStale(issuer, "abc");
        assertStale(issuer, "a.b.c");
        assertStale(issuer, "%%%.###");
    }

    @Test
    void 키가_32바이트_미만이면_설정_오류() {
        assertThatThrownBy(() -> new ShiftBatchProperties("short", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PreviewTokenCodec codec(String secret, Instant now) {
        return new PreviewTokenCodec(new ShiftBatchProperties(secret, Duration.ofMinutes(30)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String flip(String token, int index) {
        char c = token.charAt(index);
        char replacement = c == 'A' ? 'B' : 'A';
        return token.substring(0, index) + replacement + token.substring(index + 1);
    }

    private static void assertStale(PreviewTokenCodec codec, String token) {
        assertThatThrownBy(() -> codec.verify(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREVIEW_STALE));
    }
}
