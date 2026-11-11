package com.baton.shift.api;

import com.baton.integration.ActiveRequestSummary;
import com.baton.shift.api.ShiftItemResponse.AssigneeResponse;
import com.baton.shift.application.ShiftQueryService.ShiftDetailView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** ShiftItem 필드 + 상세 전용 필드. active_request는 없으면 null. */
public record ShiftDetailResponse(
        @Schema(description = "근무 id", example = "100")
        long shiftId,
        @Schema(description = "시작 시각(UTC)", example = "2026-11-11T00:00:00Z")
        Instant startsAt,
        @Schema(description = "종료 시각(UTC). 자정을 넘기면 다음 날", example = "2026-11-11T04:00:00Z")
        Instant endsAt,
        @Schema(description = "근무 날짜(starts_at의 Asia/Seoul 날짜)", example = "2026-11-11")
        LocalDate localDate,
        @Schema(description = "담당자")
        AssigneeResponse assignee,
        @Schema(description = "근무 버전. 삭제 시 expected_version으로 보낸다", example = "0")
        long version,
        @Schema(description = "내 담당 근무 여부", example = "true")
        boolean isMine,
        @Schema(description = "서버 시각 기준 시작했는지 여부", example = "false")
        boolean isStarted,
        @Schema(description = "이 근무의 진행 중 대타 요청. 없으면 null")
        ActiveRequestResponse activeRequest,
        @ArraySchema(arraySchema = @Schema(description = """
                표시용 가능 행동. 실제 변경 API가 다시 검사한다.
                매니저: delete, 승인 대기 요청이 있으면 view_approval_request 추가.
                크루원 본인 근무: 진행 중 요청이 있으면 view_request, 없고 시작 전이면 request_substitute. 그 밖에는 빈 배열""",
                example = "[\"request_substitute\"]"),
                schema = @Schema(allowableValues = {"request_substitute", "view_request", "delete",
                        "view_approval_request"}))
        List<String> allowedActions
) {

    static ShiftDetailResponse from(ShiftDetailView view) {
        ShiftItemResponse item = ShiftItemResponse.from(view.item());
        ActiveRequestSummary request = view.activeRequest();
        return new ShiftDetailResponse(item.shiftId(), item.startsAt(), item.endsAt(), item.localDate(),
                item.assignee(), view.version(), view.isMine(), view.isStarted(),
                request == null ? null : new ActiveRequestResponse(request.requestId(), request.status(),
                        request.viewerIsRequester(), request.pendingManagerApproval()),
                view.allowedActions());
    }

    public record ActiveRequestResponse(
            @Schema(description = "대타 요청 id", example = "200")
            long requestId,
            @Schema(description = "대타 요청 상태", example = "PENDING_MANAGER")
            String status,
            @Schema(description = "조회한 사용자가 요청자인지", example = "false")
            boolean viewerIsRequester,
            @Schema(description = "매니저 최종 승인 대기 중인지", example = "true")
            boolean pendingManagerApproval
    ) {
    }
}
