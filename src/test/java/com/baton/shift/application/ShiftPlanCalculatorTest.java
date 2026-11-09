package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShiftPlanCalculatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<Integer> ALL_DAYS = List.of(1, 2, 3, 4, 5, 6, 7);

    private final ShiftPlanCalculator calculator = new ShiftPlanCalculator();

    @Test
    @DisplayName("API 문서 예시: 월수금, 11-11~12-11, 2명 → 28건")
    void apiExample() {
        ShiftPlan plan = calculator.plan(new ShiftBatchInput(
                List.of(10L, 20L),
                Instant.parse("2026-11-11T00:00:00Z"),
                Instant.parse("2026-11-11T04:00:00Z"),
                repeat("2026-11-11", "2026-12-11", 1, 3, 5)));

        assertThat(plan.size()).isEqualTo(28);
        PlannedShift first = plan.shifts().getFirst();
        assertThat(first.startsAt()).isEqualTo(kst("2026-11-11", "09:00"));
        assertThat(first.endsAt()).isEqualTo(kst("2026-11-11", "13:00"));
        assertThat(first.localDate().getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(first.membershipId()).isEqualTo(10L);

        PlannedShift last = plan.shifts().getLast();
        assertThat(last.localDate()).isEqualTo(LocalDate.parse("2026-12-11"));
        assertThat(last.localDate().getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(last.membershipId()).isEqualTo(20L);

        assertThat(plan.shifts()).allSatisfy(s -> {
            assertThat(s.localDate().getDayOfWeek())
                    .isIn(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
            assertThat(s.startsAt().atZone(SEOUL).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(Duration.between(s.startsAt(), s.endsAt())).isEqualTo(Duration.ofHours(4));
        });
        assertThat(plan.shifts().stream().filter(s -> s.membershipId() == 10L)).hasSize(14);
        assertThat(plan.shifts()).isSortedAccordingTo((a, b) -> {
            int c = a.startsAt().compareTo(b.startsAt());
            return c != 0 ? c : Long.compare(a.membershipId(), b.membershipId());
        });
    }

    @Test
    @DisplayName("2026-11-04 시작: 12-04 허용, 12-05 거부")
    void maxEndDate_normalMonth() {
        Instant s = kst("2026-11-04", "09:00");
        Instant e = s.plus(Duration.ofHours(4));

        ShiftPlan ok = calculator.plan(input(List.of(1L), s, e, repeat("2026-11-04", "2026-12-04", ALL_DAYS)));
        assertThat(ok.size()).isEqualTo(31);
        assertThat(ok.shifts().getLast().localDate()).isEqualTo(LocalDate.parse("2026-12-04"));

        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-04", "2026-12-05", ALL_DAYS))),
                ShiftPlanCalculator.F_REPEAT_END);
    }

    @Test
    @DisplayName("2027-01-31 시작: 02-28 허용, 03-01 거부 (말일 보정)")
    void maxEndDate_monthEnd() {
        Instant s = kst("2027-01-31", "09:00");
        Instant e = s.plus(Duration.ofHours(4));

        ShiftPlan ok = calculator.plan(input(List.of(1L), s, e, repeat("2027-01-31", "2027-02-28", ALL_DAYS)));
        assertThat(ok.size()).isEqualTo(29);
        assertThat(ok.shifts().getLast().localDate()).isEqualTo(LocalDate.parse("2027-02-28"));

        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2027-01-31", "2027-03-01", ALL_DAYS))),
                ShiftPlanCalculator.F_REPEAT_END);
    }

    @Test
    @DisplayName("22:00~다음 날 06:00 반복: 모두 8시간, local_date는 시작일")
    void overnightRepeat() {
        ShiftPlan plan = calculator.plan(input(List.of(1L),
                kst("2026-11-10", "22:00"), kst("2026-11-11", "06:00"),
                repeat("2026-11-10", "2026-11-20", ALL_DAYS)));

        assertThat(plan.size()).isEqualTo(11);
        assertThat(plan.shifts()).allSatisfy(s -> {
            assertThat(Duration.between(s.startsAt(), s.endsAt())).isEqualTo(Duration.ofHours(8));
            assertThat(s.startsAt().atZone(SEOUL).toLocalTime()).isEqualTo(LocalTime.of(22, 0));
            assertThat(s.endsAt().atZone(SEOUL).toLocalDate()).isEqualTo(s.localDate().plusDays(1));
            assertThat(s.localDate()).isEqualTo(s.startsAt().atZone(SEOUL).toLocalDate());
        });
        assertThat(plan.shifts().getFirst().localDate()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(plan.shifts().getLast().localDate()).isEqualTo(LocalDate.parse("2026-11-20"));
    }

    @Test
    @DisplayName("길이 0분·24시간 정각·음수 거부, 23시간 59분 허용")
    void lengthBounds() {
        Instant s = kst("2026-11-11", "09:00");

        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, s, null)), ShiftPlanCalculator.F_ENDS_AT);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, s.plus(Duration.ofHours(24)), null)),
                ShiftPlanCalculator.F_ENDS_AT);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, s.minus(Duration.ofMinutes(1)), null)),
                ShiftPlanCalculator.F_ENDS_AT);

        Instant e = s.plus(Duration.ofHours(23).plusMinutes(59));
        ShiftPlan ok = calculator.plan(input(List.of(1L), s, e, null));
        assertThat(ok.shifts()).containsExactly(new PlannedShift(1L, s, e, LocalDate.parse("2026-11-11")));
    }

    @Test
    @DisplayName("요일 0개·8·기간에 없는 요일만 선택 → 거부")
    void invalidWeekdays() {
        Instant s = kst("2026-11-11", "09:00"); // 수요일
        Instant e = s.plus(Duration.ofHours(4));

        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-20", List.of()))),
                ShiftPlanCalculator.F_REPEAT_WEEKDAYS);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-20", 8))),
                ShiftPlanCalculator.F_REPEAT_WEEKDAYS);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-20", 0))),
                ShiftPlanCalculator.F_REPEAT_WEEKDAYS);
        // 11-11(수)~11-12(목)에 월요일 없음
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-12", 1))),
                ShiftPlanCalculator.F_REPEAT_WEEKDAYS);
    }

    @Test
    @DisplayName("반복 미사용 3명 → 같은 시간 3건")
    void noRepeat_threeAssignees() {
        Instant s = kst("2026-11-11", "09:00");
        Instant e = kst("2026-11-11", "18:00");
        ShiftBatchInput.Repeat ignored = new ShiftBatchInput.Repeat(false, null, null, null);

        ShiftPlan plan = calculator.plan(input(List.of(30L, 10L, 20L), s, e, ignored));

        assertThat(plan.shifts()).containsExactly(
                new PlannedShift(10L, s, e, LocalDate.parse("2026-11-11")),
                new PlannedShift(20L, s, e, LocalDate.parse("2026-11-11")),
                new PlannedShift(30L, s, e, LocalDate.parse("2026-11-11")));
    }

    @Test
    @DisplayName("담당자 비어 있음·null·중복 → 거부")
    void invalidAssignees() {
        Instant s = kst("2026-11-11", "09:00");
        Instant e = s.plus(Duration.ofHours(4));

        assertFieldErrors(() -> calculator.plan(input(List.of(), s, e, null)), ShiftPlanCalculator.F_ASSIGNEES);
        assertFieldErrors(() -> calculator.plan(input(null, s, e, null)), ShiftPlanCalculator.F_ASSIGNEES);
        assertFieldErrors(() -> calculator.plan(input(Arrays.asList(1L, null), s, e, null)),
                ShiftPlanCalculator.F_ASSIGNEES);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L, 1L), s, e, null)), ShiftPlanCalculator.F_ASSIGNEES);
    }

    @Test
    @DisplayName("반복 시작일이 첫 근무 날짜(KST)와 다르면 거부")
    void repeatStartMismatch() {
        // 2026-11-10T15:00Z = 2026-11-11 00:00 KST
        Instant s = Instant.parse("2026-11-10T15:00:00Z");
        Instant e = s.plus(Duration.ofHours(4));

        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-10", "2026-11-20", ALL_DAYS))),
                ShiftPlanCalculator.F_REPEAT_START);
        assertThat(calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-20", ALL_DAYS))).size())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("필수값 누락·역전 종료일 → 필드별로 거부")
    void missingFields() {
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), null, null, null)),
                ShiftPlanCalculator.F_STARTS_AT, ShiftPlanCalculator.F_ENDS_AT);

        Instant s = kst("2026-11-11", "09:00");
        Instant e = s.plus(Duration.ofHours(4));
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e,
                        new ShiftBatchInput.Repeat(true, null, null, List.of(1)))),
                ShiftPlanCalculator.F_REPEAT_START, ShiftPlanCalculator.F_REPEAT_END);
        assertFieldErrors(() -> calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-11-10", ALL_DAYS))),
                ShiftPlanCalculator.F_REPEAT_END);
    }

    @Test
    @DisplayName("여러 위반은 한 예외에 모두 담긴다")
    void collectsAllErrors() {
        Instant s = kst("2026-11-11", "09:00");

        assertFieldErrors(() -> calculator.plan(input(List.of(1L, 1L), s, s, repeat("2026-11-11", "2026-12-31", List.of()))),
                ShiftPlanCalculator.F_ASSIGNEES, ShiftPlanCalculator.F_ENDS_AT,
                ShiftPlanCalculator.F_REPEAT_END, ShiftPlanCalculator.F_REPEAT_WEEKDAYS);
    }

    @Test
    @DisplayName("결과가 2000건을 넘으면 거부, 2000건 이하는 허용")
    void maxPlannedShifts() {
        Instant s = kst("2026-12-01", "09:00");
        Instant e = s.plus(Duration.ofHours(4));
        ShiftBatchInput.Repeat daily32 = repeat("2026-12-01", "2027-01-01", ALL_DAYS); // 32일

        List<Long> people63 = LongStream.rangeClosed(1, 63).boxed().toList(); // 2016건
        assertFieldErrors(() -> calculator.plan(input(people63, s, e, daily32)), ShiftPlanCalculator.F_ASSIGNEES);

        List<Long> people62 = LongStream.rangeClosed(1, 62).boxed().toList(); // 1984건
        assertThat(calculator.plan(input(people62, s, e, daily32)).size()).isEqualTo(1984);
    }

    @Test
    @DisplayName("요일 중복은 제거된다")
    void duplicateWeekdays() {
        Instant s = kst("2026-11-11", "09:00");
        Instant e = s.plus(Duration.ofHours(4));

        ShiftPlan withDup = calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-12-11", 1, 1, 3)));
        ShiftPlan without = calculator.plan(input(List.of(1L), s, e, repeat("2026-11-11", "2026-12-11", 1, 3)));

        assertThat(withDup).isEqualTo(without);
    }

    // --- helpers ---

    private static ShiftBatchInput input(List<Long> ids, Instant s, Instant e, ShiftBatchInput.Repeat repeat) {
        return new ShiftBatchInput(ids, s, e, repeat);
    }

    private static ShiftBatchInput.Repeat repeat(String start, String end, Integer... weekdays) {
        return repeat(start, end, List.of(weekdays));
    }

    private static ShiftBatchInput.Repeat repeat(String start, String end, List<Integer> weekdays) {
        return new ShiftBatchInput.Repeat(true, LocalDate.parse(start), LocalDate.parse(end), weekdays);
    }

    private static Instant kst(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    private static void assertFieldErrors(ThrowingCallable call, String... expectedFields) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    List<String> fields = new ArrayList<>(ex.getFieldErrors().stream().map(FieldError::field).toList());
                    assertThat(fields).containsExactlyInAnyOrder(expectedFields);
                });
    }
}
