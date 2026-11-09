package com.baton.integration;

/**
 * A가 구현, B가 호출. 시그니처는 docs/backend-split.md 기준.
 *
 * <p>잠금 순서: membership → shift → substitute_request. 승인 시 호출자(B)는 후보 소속을
 * {@link MembershipQuery#lockActiveById}로 먼저 잠근 뒤 {@link #reassign}을 호출한다.
 *
 * <p>매장 접근 권한(호출자가 그 매장의 활성 소속인지)은 이 인터페이스가 검사하지 않는다.
 * 호출자가 {@link MembershipQuery#requireActive}로 먼저 확인한다.
 */
public interface ShiftAssignment {

    /**
     * 대타 승인에 따른 담당자 변경. 승인 트랜잭션 안에서만 호출한다(호출자 트랜잭션이 없으면
     * IllegalTransactionStateException). 근무 행을 잠그고 아래를 모두 확인한 뒤 담당자를 바꾼다.
     * <ul>
     *   <li>근무가 있고 삭제되지 않았다</li>
     *   <li>현재 version이 expectedVersion과 같다</li>
     *   <li>현재 담당자가 fromMembershipId이고, toMembershipId가 그와 다르다</li>
     * </ul>
     * 하나라도 어긋나면 BusinessException(SHIFT_STATE_CONFLICT). 성공하면 version이 1 올라가고
     * 근무 처리 기록(shift_events, REASSIGNED)을 같은 트랜잭션에 남긴다.
     * toMembershipId가 같은 매장의 활성 소속인지는 호출자가 잠금 조회로 먼저 확인한다.
     */
    void reassign(long shiftId, long fromMembershipId, long toMembershipId, long expectedVersion);

    /**
     * 요청 생성 검증과 스냅샷용 현재 근무 상태. 잠그지 않는다. 삭제된 근무도 deleted=true로 돌려준다.
     * 없는 근무면 BusinessException(NOT_FOUND).
     */
    ShiftSnapshot getSnapshot(long shiftId);
}
