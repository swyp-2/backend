package com.baton.shift.api;

import com.baton.integration.ActiveRequestSummary;
import com.baton.shift.api.ShiftItemResponse.AssigneeResponse;
import com.baton.shift.application.ShiftQueryService.ShiftDetailView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** ShiftItem 필드 + 상세 전용 필드. active_request는 없으면 null. */
public record ShiftDetailResponse(long shiftId, Instant startsAt, Instant endsAt, LocalDate localDate,
                                  AssigneeResponse assignee, long version, boolean isMine, boolean isStarted,
                                  ActiveRequestResponse activeRequest, List<String> allowedActions) {

    static ShiftDetailResponse from(ShiftDetailView view) {
        ShiftItemResponse item = ShiftItemResponse.from(view.item());
        ActiveRequestSummary request = view.activeRequest();
        return new ShiftDetailResponse(item.shiftId(), item.startsAt(), item.endsAt(), item.localDate(),
                item.assignee(), view.version(), view.isMine(), view.isStarted(),
                request == null ? null : new ActiveRequestResponse(request.requestId(), request.status(),
                        request.viewerIsRequester(), request.pendingManagerApproval()),
                view.allowedActions());
    }

    public record ActiveRequestResponse(long requestId, String status, boolean viewerIsRequester,
                                        boolean pendingManagerApproval) {
    }
}
