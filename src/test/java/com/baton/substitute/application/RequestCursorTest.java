package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestCursorTest {

    @Test
    void 인코딩한_커서를_그대로_복원한다() {
        RequestCursor cursor = new RequestCursor(Instant.parse("2026-11-09T03:00:00.123456Z"), 151L);

        assertThat(RequestCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void 없거나_빈_값이면_첫_페이지() {
        assertThat(RequestCursor.decode(null)).isNull();
        assertThat(RequestCursor.decode("")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-cursor!", "abc", "MTIz", "OjE1MQ", "MTIzOmFiYw", "MTIzOjA"})
    void 형식이_틀리면_VALIDATION_FAILED(String value) {
        // MTIz = "123", OjE1MQ = ":151", MTIzOmFiYw = "123:abc", MTIzOjA = "123:0"
        assertThatThrownBy(() -> RequestCursor.decode(value))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void 클라이언트가_읽을_수_있는_시각_문자열이_아니다() {
        String encoded = new RequestCursor(Instant.parse("2026-11-09T03:00:00Z"), 1L).encode();

        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)).doesNotContain("2026");
    }
}
