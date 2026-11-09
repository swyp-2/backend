package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftBatchHashesTest {

    private static final Instant S = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant E = Instant.parse("2026-11-11T04:00:00Z");

    @Test
    void 담당자와_요일_순서가_달라도_입력_해시는_같다() {
        String a = ShiftBatchHashes.inputHash(1L, input(List.of(10L, 20L), repeat(true, 1, 3, 5)));
        String b = ShiftBatchHashes.inputHash(1L, input(List.of(20L, 10L), repeat(true, 5, 1, 3)));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void 반복을_쓰지_않으면_반복_필드는_무시한다() {
        String none = ShiftBatchHashes.inputHash(1L, input(List.of(10L), null));
        String disabled = ShiftBatchHashes.inputHash(1L, input(List.of(10L), repeat(false, 2, 4)));

        assertThat(none).isEqualTo(disabled);
    }

    @Test
    void 매장_담당자_시각_반복이_다르면_입력_해시가_다르다() {
        String base = ShiftBatchHashes.inputHash(1L, input(List.of(10L, 20L), repeat(true, 1, 3, 5)));

        assertThat(ShiftBatchHashes.inputHash(2L, input(List.of(10L, 20L), repeat(true, 1, 3, 5)))).isNotEqualTo(base);
        assertThat(ShiftBatchHashes.inputHash(1L, input(List.of(10L), repeat(true, 1, 3, 5)))).isNotEqualTo(base);
        assertThat(ShiftBatchHashes.inputHash(1L, input(List.of(10L, 20L), repeat(true, 1, 3)))).isNotEqualTo(base);
        assertThat(ShiftBatchHashes.inputHash(1L, new ShiftBatchInput(List.of(10L, 20L), S, E.plusSeconds(60),
                repeat(true, 1, 3, 5)))).isNotEqualTo(base);
    }

    @Test
    void 생성_대상이_한_건만_달라도_계획_해시가_다르다() {
        PlannedShift a = new PlannedShift(10L, S, E, LocalDate.parse("2026-11-11"));
        PlannedShift b = new PlannedShift(20L, S, E, LocalDate.parse("2026-11-11"));

        assertThat(ShiftBatchHashes.planHash(List.of(a, b))).isEqualTo(ShiftBatchHashes.planHash(List.of(a, b)));
        assertThat(ShiftBatchHashes.planHash(List.of(a, b))).isNotEqualTo(ShiftBatchHashes.planHash(List.of(a)));
        assertThat(ShiftBatchHashes.planHash(List.of())).isNotEqualTo(ShiftBatchHashes.planHash(List.of(a)));
    }

    private static ShiftBatchInput input(List<Long> ids, ShiftBatchInput.Repeat repeat) {
        return new ShiftBatchInput(ids, S, E, repeat);
    }

    private static ShiftBatchInput.Repeat repeat(boolean enabled, Integer... weekdays) {
        return new ShiftBatchInput.Repeat(enabled, LocalDate.parse("2026-11-11"), LocalDate.parse("2026-12-11"),
                List.of(weekdays));
    }
}
