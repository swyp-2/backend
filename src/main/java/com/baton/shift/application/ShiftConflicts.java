package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.shift.domain.Shift;

/**
 * 근무 상태 충돌(409 SHIFT_STATE_CONFLICT)과 current_state 값.
 *
 * <p>current_state에는 문자열만 담는다. 프론트는 이 값을 보고 최신 근무를 다시 조회한다(정식 동작).
 * <ul>
 *   <li>{@link #DELETED}: 이미 삭제된 근무</li>
 *   <li>{@link #CHANGED}: 버전·담당자가 바뀌었거나 변경 대상이 더 이상 유효하지 않음</li>
 * </ul>
 */
final class ShiftConflicts {

    static final String DELETED = "DELETED";
    static final String CHANGED = "CHANGED";

    private ShiftConflicts() {
    }

    /** 삭제되지 않았고 version이 기대값과 같아야 한다. */
    static void requireLive(Shift shift, long expectedVersion) {
        if (shift.isDeleted()) {
            throw BusinessException.withCurrentState(ErrorCode.SHIFT_STATE_CONFLICT, DELETED);
        }
        if (shift.getVersion() != expectedVersion) {
            throw changed();
        }
    }

    static BusinessException changed() {
        return BusinessException.withCurrentState(ErrorCode.SHIFT_STATE_CONFLICT, CHANGED);
    }
}
