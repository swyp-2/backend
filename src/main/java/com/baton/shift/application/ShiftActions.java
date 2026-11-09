package com.baton.shift.application;

import com.baton.integration.ActiveRequestSummary;
import com.baton.member.domain.Role;
import java.util.List;

/**
 * 근무 상세(SH-01/02)의 가능 행동. 서버 Clock 기준 시작 여부로 계산한다.
 * 매니저에게는 대타 요청·근무 수정 행동을 절대 주지 않는다(SCH-02).
 */
public final class ShiftActions {

    public static final String REQUEST_SUBSTITUTE = "request_substitute";
    public static final String VIEW_REQUEST = "view_request";
    public static final String DELETE = "delete";
    public static final String VIEW_APPROVAL_REQUEST = "view_approval_request";

    private ShiftActions() {
    }

    /** @param activeRequest 없으면 null */
    public static List<String> of(Role viewerRole, boolean isMine, boolean isStarted,
                                  ActiveRequestSummary activeRequest) {
        if (viewerRole == Role.MANAGER) {
            // 과거 근무도 삭제 가능(SCH-07). 승인 대기 요청이 있으면 AP-02 진입 유지(알림 만료와 무관).
            if (activeRequest != null && activeRequest.pendingManagerApproval()) {
                return List.of(DELETE, VIEW_APPROVAL_REQUEST);
            }
            return List.of(DELETE);
        }
        if (!isMine) {
            return List.of();
        }
        if (activeRequest != null) {
            return List.of(VIEW_REQUEST);
        }
        return isStarted ? List.of() : List.of(REQUEST_SUBSTITUTE);
    }
}
