package com.baton.integration;

import java.util.Optional;

/**
 * B가 구현, A가 호출. 시그니처는 docs/backend-split.md 기준.
 *
 * <p>잠금 순서: membership → shift → substitute_request. 호출자(A)가 membership/shift를 먼저 잠근 뒤 호출한다.
 */
public interface SubstituteRequestLifecycle {

    /**
     * 근무 삭제 트랜잭션 안에서 호출. 활성 요청을 삭제 사유로 종료. 종료한 요청 id 반환.
     * 호출자 트랜잭션이 없으면 IllegalTransactionStateException.
     */
    Optional<Long> closeBySourceDeleted(long shiftId, long actorMembershipId);

    /**
     * 나가기·탈퇴 트랜잭션 안에서 호출. 요청자/후보/지정 대상 정리 (기능명세 SUB-10 규칙).
     * 호출자 트랜잭션이 없으면 IllegalTransactionStateException.
     */
    void cleanupOnMembershipLeave(long membershipId);

    /** 근무 상세(SH-01/02)에 붙일 활성 요청 요약. 없으면 empty. */
    Optional<ActiveRequestSummary> findActiveByShift(long shiftId, long viewerMembershipId);

    /** 탈퇴 파기: 요청·이벤트 스냅샷의 개인 정보 제거, 본인 수신 알림 삭제. */
    void anonymizeFor(long membershipId);
}
