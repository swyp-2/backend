package com.baton.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameTest {

    private static final String EMOJI = "😀"; // 😀, 서로게이트 쌍(UTF-16 2개) = 코드 포인트 1개

    @Test
    void 앞뒤_공백을_제거한다() {
        assertThat(DisplayName.normalize(" 김도연 ")).isEqualTo("김도연");
        assertThat(DisplayName.normalize("　박소율　")).isEqualTo("박소율"); // 전각 공백도 strip 대상
    }

    @Test
    void 경계_2자와_15자는_통과() {
        assertThat(DisplayName.normalize("김도")).isEqualTo("김도");
        assertThat(DisplayName.normalize("가".repeat(15))).hasSize(15);
    }

    @Test
    void 이모지는_코드_포인트_1자로_센다() {
        String fifteen = "김도연" + EMOJI.repeat(12);
        assertThat(fifteen.length()).isEqualTo(27);
        assertThat(DisplayName.normalize(fifteen)).isEqualTo(fifteen);

        assertInvalid(fifteen + EMOJI);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "김", " 김 ", "가가가가가가가가가가가가가가가가"})
    void 길이_범위를_벗어나면_거부(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"김\n도연", "김도연\n", "\n김도연", "김\r도연", "김\t도연", "김\u0000도연", "김 도연"})
    void 줄바꿈과_제어문자는_위치와_관계없이_거부(String raw) {
        assertInvalid(raw);
    }

    private static void assertInvalid(String raw) {
        assertThatThrownBy(() -> DisplayName.normalize(raw))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(be.getFieldErrors()).singleElement()
                            .satisfies(fe -> assertThat(fe.field()).isEqualTo("display_name"));
                });
    }
}
