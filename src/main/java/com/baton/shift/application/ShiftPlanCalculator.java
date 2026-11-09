package com.baton.shift.application;

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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 근무 일괄 등록 계획 계산(SCH-03/04/05, 정책 B04·B22·D03·D08·D14). DB를 쓰지 않는 순수 계산.
 * 완전 동일 중복 제외(B05)는 DB 조회가 필요하므로 호출 측이 처리한다.
 * 검증 실패는 필드별 사유를 모아 VALIDATION_FAILED 한 번으로 던진다.
 */
@Component
public class ShiftPlanCalculator {

    public static final int MAX_PLANNED_SHIFTS = 2000;

    static final String F_ASSIGNEES = "assignee_membership_ids";
    static final String F_STARTS_AT = "starts_at";
    static final String F_ENDS_AT = "ends_at";
    static final String F_REPEAT_START = "repeat.start_date";
    static final String F_REPEAT_END = "repeat.end_date";
    static final String F_REPEAT_WEEKDAYS = "repeat.weekdays";

    private static final ZoneId ZONE = ShiftQueryService.STORE_ZONE;
    private static final Duration MAX_LENGTH_EXCLUSIVE = Duration.ofHours(24);

    private static final Comparator<PlannedShift> ORDER =
            Comparator.comparing(PlannedShift::startsAt).thenComparingLong(PlannedShift::membershipId);

    public ShiftPlan plan(ShiftBatchInput input) {
        List<FieldError> errors = new ArrayList<>();

        List<Long> assignees = validateAssignees(input.assigneeMembershipIds(), errors);
        Duration length = validateTimes(input.startsAt(), input.endsAt(), errors);

        List<LocalDate> dates;
        if (input.repeatEnabled()) {
            dates = validateRepeat(input.repeat(), input.startsAt(), errors);
        } else {
            dates = input.startsAt() == null ? null : List.of(localDate(input.startsAt()));
        }

        if (errors.isEmpty()) {
            long expected = (long) dates.size() * assignees.size();
            if (expected > MAX_PLANNED_SHIFTS) {
                errors.add(new FieldError(F_ASSIGNEES, "한 번에 등록할 수 있는 근무 수(" + MAX_PLANNED_SHIFTS + "건)를 넘었어요."));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, errors);
        }

        LocalTime baseTime = input.startsAt().atZone(ZONE).toLocalTime();
        List<PlannedShift> shifts = new ArrayList<>(dates.size() * assignees.size());
        for (LocalDate date : dates) {
            Instant startsAt = input.repeatEnabled() ? date.atTime(baseTime).atZone(ZONE).toInstant() : input.startsAt();
            Instant endsAt = startsAt.plus(length);
            for (Long membershipId : assignees) {
                shifts.add(new PlannedShift(membershipId, startsAt, endsAt, localDate(startsAt)));
            }
        }
        shifts.sort(ORDER);
        return new ShiftPlan(shifts);
    }

    private List<Long> validateAssignees(List<Long> ids, List<FieldError> errors) {
        if (ids == null || ids.isEmpty()) {
            errors.add(new FieldError(F_ASSIGNEES, "근무자를 1명 이상 선택해 주세요."));
            return List.of();
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            errors.add(new FieldError(F_ASSIGNEES, "근무자 값이 올바르지 않아요."));
            return List.of();
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            errors.add(new FieldError(F_ASSIGNEES, "같은 근무자가 중복 선택됐어요."));
            return List.of();
        }
        return ids;
    }

    /** 유효하면 근무 길이, 아니면 null. 길이가 0 초과·24시간 미만이면 자정 넘김은 자동으로 익일 종료 1건이다. */
    private Duration validateTimes(Instant startsAt, Instant endsAt, List<FieldError> errors) {
        if (startsAt == null) {
            errors.add(new FieldError(F_STARTS_AT, "시작 시각을 입력해 주세요."));
        }
        if (endsAt == null) {
            errors.add(new FieldError(F_ENDS_AT, "종료 시각을 입력해 주세요."));
        }
        if (startsAt == null || endsAt == null) {
            return null;
        }
        // 완전 동일 중복(B05)을 시각 일치로 판정하므로 분 단위만 받는다(2026-11-10 결정).
        boolean startsOk = isWholeMinute(startsAt);
        boolean endsOk = isWholeMinute(endsAt);
        if (!startsOk) {
            errors.add(new FieldError(F_STARTS_AT, "시작 시각은 분 단위로 입력해 주세요."));
        }
        if (!endsOk) {
            errors.add(new FieldError(F_ENDS_AT, "종료 시각은 분 단위로 입력해 주세요."));
        }
        if (!startsOk || !endsOk) {
            return null;
        }
        Duration length = Duration.between(startsAt, endsAt);
        if (length.isNegative() || length.isZero()) {
            errors.add(new FieldError(F_ENDS_AT, "종료 시각은 시작 시각보다 늦어야 해요."));
            return null;
        }
        if (length.compareTo(MAX_LENGTH_EXCLUSIVE) >= 0) {
            errors.add(new FieldError(F_ENDS_AT, "근무 길이는 24시간 미만이어야 해요."));
            return null;
        }
        return length;
    }

    /** 유효하면 반복 대상 날짜(오름차순), 아니면 null. */
    private List<LocalDate> validateRepeat(ShiftBatchInput.Repeat repeat, Instant startsAt, List<FieldError> errors) {
        LocalDate start = repeat.startDate();
        LocalDate end = repeat.endDate();

        if (start == null) {
            errors.add(new FieldError(F_REPEAT_START, "반복 시작일을 입력해 주세요."));
        } else if (startsAt != null && !start.equals(localDate(startsAt))) {
            errors.add(new FieldError(F_REPEAT_START, "반복 시작일은 첫 근무 날짜와 같아야 해요."));
        }

        boolean rangeValid = false;
        if (end == null) {
            errors.add(new FieldError(F_REPEAT_END, "반복 종료일을 입력해 주세요."));
        } else if (start != null) {
            if (end.isBefore(start)) {
                errors.add(new FieldError(F_REPEAT_END, "종료일이 시작일보다 빠를 수 없어요."));
            } else if (end.isAfter(start.plusMonths(1))) {
                // plusMonths는 다음 달에 같은 일자가 없으면 말일로 보정한다(B04: 2027-01-31 → 2027-02-28).
                errors.add(new FieldError(F_REPEAT_END, "반복 기간은 시작일부터 최대 1개월이에요."));
            } else {
                rangeValid = true;
            }
        }

        Set<DayOfWeek> weekdays = validateWeekdays(repeat.weekdays(), errors);
        if (!rangeValid || weekdays == null) {
            return null;
        }

        List<LocalDate> dates = start.datesUntil(end.plusDays(1))
                .filter(d -> weekdays.contains(d.getDayOfWeek()))
                .toList();
        if (dates.isEmpty()) {
            errors.add(new FieldError(F_REPEAT_WEEKDAYS, "기간 안에 선택한 요일이 없어요."));
            return null;
        }
        return dates;
    }

    /** 유효하면 중복 제거된 요일 집합, 아니면 null. */
    private Set<DayOfWeek> validateWeekdays(List<Integer> values, List<FieldError> errors) {
        if (values == null || values.isEmpty()) {
            errors.add(new FieldError(F_REPEAT_WEEKDAYS, "요일을 1개 이상 선택해 주세요."));
            return null;
        }
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        for (Integer v : values) {
            if (v == null || v < 1 || v > 7) {
                errors.add(new FieldError(F_REPEAT_WEEKDAYS, "요일 값이 올바르지 않아요."));
                return null;
            }
            result.add(DayOfWeek.of(v));
        }
        return result;
    }

    private static boolean isWholeMinute(Instant instant) {
        return Math.floorMod(instant.getEpochSecond(), 60) == 0 && instant.getNano() == 0;
    }

    private static LocalDate localDate(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }
}
