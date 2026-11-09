package com.baton.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreNameTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600)); // UTF-16으로 2자

    @Test
    void 앞뒤_공백을_제거한다() {
        assertThat(StoreName.normalize("  바톤 카페 ")).isEqualTo("바톤 카페");
    }

    @Test
    void 한_글자와_15자는_허용() {
        assertThat(StoreName.normalize("가")).isEqualTo("가");
        assertThat(StoreName.normalize("가".repeat(15))).hasSize(15);
    }

    @Test
    void 이모지는_코드_포인트_하나로_센다() {
        assertThat(StoreName.normalize(EMOJI.repeat(15))).isEqualTo(EMOJI.repeat(15));
        assertInvalid(EMOJI.repeat(16));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　", "가나다라마바사아자차카타파하가나"})
    void 빈_값과_16자_이상은_거부(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"바톤\n카페", "바톤\t카페", "바톤 카페", "바톤카페\n"})
    void 줄바꿈과_제어문자는_위치와_관계없이_거부(String raw) {
        assertInvalid(raw);
    }

    private static void assertInvalid(String raw) {
        assertThatThrownBy(() -> StoreName.normalize(raw))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getFieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("name"));
                });
    }
}
