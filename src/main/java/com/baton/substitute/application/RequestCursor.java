package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * 요청 목록 커서: 마지막 항목의 (생성 시각, id). 클라이언트에는 불투명 문자열로만 준다.
 * 형식: base64url("{생성 시각 epoch 마이크로초}:{id}"). DB(timestamptz)의 정밀도가 마이크로초라 그 단위로 맞춘다.
 */
public record RequestCursor(Instant createdAt, long id) {

    private static final String FIELD = "cursor";

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** null·빈 값이면 null(첫 페이지). 형식이 틀리면 VALIDATION_FAILED. */
    public static RequestCursor decode(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator <= 0) {
                throw invalid();
            }
            long micros = Long.parseLong(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            if (id <= 0) {
                throw invalid();
            }
            return new RequestCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                List.of(new FieldError(FIELD, "목록을 처음부터 다시 불러와 주세요.")));
    }
}
