package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.ShiftItemView;
import java.time.Instant;
import java.time.LocalDate;

/** 근무 항목(ShiftItem). local_date는 starts_at의 Asia/Seoul 날짜. */
public record ShiftItemResponse(long shiftId, Instant startsAt, Instant endsAt, LocalDate localDate,
                                AssigneeResponse assignee) {

    static ShiftItemResponse from(ShiftItemView view) {
        return new ShiftItemResponse(view.shiftId(), view.startsAt(), view.endsAt(), view.localDate(),
                new AssigneeResponse(view.assignee().membershipId(), view.assignee().displayName(),
                        view.assignee().personalColor(), view.assignee().isMe()));
    }

    public record AssigneeResponse(long membershipId, String displayName, String personalColor, boolean isMe) {
    }
}
