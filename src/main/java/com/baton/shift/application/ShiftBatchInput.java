package com.baton.shift.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 근무 일괄 등록 입력(SCH-03/04). starts_at·ends_at은 첫 근무 1건의 시작·종료.
 * repeat가 null이거나 enabled=false면 반복 필드는 무시한다.
 */
public record ShiftBatchInput(
        List<Long> assigneeMembershipIds,
        Instant startsAt,
        Instant endsAt,
        Repeat repeat
) {

    /** weekdays는 ISO 요일(1=월 ~ 7=일). start_date·end_date 모두 반복 대상에 포함. */
    public record Repeat(boolean enabled, LocalDate startDate, LocalDate endDate, List<Integer> weekdays) {
    }

    public boolean repeatEnabled() {
        return repeat != null && repeat.enabled();
    }
}
