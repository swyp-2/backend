package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.ShiftItemView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/** 근무 항목(ShiftItem). local_date는 starts_at의 Asia/Seoul 날짜. */
public record ShiftItemResponse(
        @Schema(description = "근무 id", example = "100")
        long shiftId,
        @Schema(description = "시작 시각(UTC)", example = "2026-11-11T00:00:00Z")
        Instant startsAt,
        @Schema(description = "종료 시각(UTC). 자정을 넘기면 다음 날", example = "2026-11-11T04:00:00Z")
        Instant endsAt,
        @Schema(description = "근무 날짜(starts_at의 Asia/Seoul 날짜)", example = "2026-11-11")
        LocalDate localDate,
        @Schema(description = "담당자")
        AssigneeResponse assignee
) {

    static ShiftItemResponse from(ShiftItemView view) {
        return new ShiftItemResponse(view.shiftId(), view.startsAt(), view.endsAt(), view.localDate(),
                new AssigneeResponse(view.assignee().membershipId(), view.assignee().displayName(),
                        view.assignee().personalColor(), view.assignee().isMe()));
    }

    public record AssigneeResponse(
            @Schema(description = "담당자 소속 id", example = "10")
            long membershipId,
            @Schema(description = "담당자 이름. 탈퇴했으면 \"탈퇴한 구성원\"", example = "김도연")
            String displayName,
            @Schema(description = "개인 색상(#RRGGBB 대문자). 탈퇴했으면 #B0B4BA", example = "#E5484D")
            String personalColor,
            @Schema(description = "조회한 사용자 본인 여부", example = "true")
            boolean isMe
    ) {
    }
}
