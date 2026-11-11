package com.baton.shift.application;

import com.baton.shift.domain.Shift;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** shift_events의 before/after_value JSON. 키는 snake_case(전역 JsonMapper 설정). */
@Component
@RequiredArgsConstructor
class ShiftEventValues {

    private final JsonMapper jsonMapper;

    /** 담당자·시작·종료 스냅샷. DELETED의 before_value(CREATED의 after_value와 같은 모양). */
    String snapshot(Shift shift) {
        return jsonMapper.writeValueAsString(new ShiftValue(shift.getAssigneeMembershipId(), shift.getStartsAt(),
                shift.getEndsAt()));
    }

    /** REASSIGNED의 before/after_value. */
    String assignee(long membershipId) {
        return jsonMapper.writeValueAsString(new AssigneeValue(membershipId));
    }

    record ShiftValue(long assigneeMembershipId, Instant startsAt, Instant endsAt) {
    }

    record AssigneeValue(long assigneeMembershipId) {
    }
}
