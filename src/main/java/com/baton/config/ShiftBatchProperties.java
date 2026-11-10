package com.baton.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * baton.shift.* 설정. prod의 preview-secret은 환경변수(BATON_SHIFT_PREVIEW_SECRET)로만 주입한다.
 *
 * @param previewSecret 근무 일괄 등록 미리보기 토큰의 HMAC-SHA256 키. UTF-8 32바이트 이상
 * @param previewTtl    미리보기 토큰 유효 시간
 */
@ConfigurationProperties("baton.shift")
public record ShiftBatchProperties(
        String previewSecret,
        @DefaultValue("30m") Duration previewTtl
) {

    static final int MIN_SECRET_BYTES = 32;

    public ShiftBatchProperties {
        if (previewSecret == null || previewSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("baton.shift.preview-secret은 UTF-8 기준 32바이트 이상이어야 합니다.");
        }
        if (previewTtl == null || previewTtl.isNegative() || previewTtl.isZero()) {
            throw new IllegalArgumentException("baton.shift.preview-ttl은 0보다 커야 합니다.");
        }
    }
}
