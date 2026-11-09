package com.baton.integration;

/**
 * 근무 상세에 붙일 활성 대타 요청 요약.
 *
 * @param status 대타 요청 상태 이름 (substitute_requests.status 값)
 */
public record ActiveRequestSummary(long requestId, String status, boolean viewerIsRequester,
                                   boolean pendingManagerApproval) {
}
