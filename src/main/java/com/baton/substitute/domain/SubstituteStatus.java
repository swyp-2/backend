package com.baton.substitute.domain;

import java.util.Set;

/**
 * 대타 요청 상태. WAITING·PENDING_MANAGER만 진행 중이고 나머지는 종료 상태(다시 열지 않음).
 */
public enum SubstituteStatus {
    WAITING,
    PENDING_MANAGER,
    APPROVED,
    REJECTED,
    ALL_DECLINED,
    CANCELLED,
    WITHDRAWN,
    EXPIRED,
    /** 원본 근무 삭제로 종료. 사유는 {@link TerminalReason#SHIFT_DELETED}. */
    CANCELLED_SOURCE_CHANGED;

    /** 진행 중 상태. substitute_requests_shift_active_uk 인덱스 조건과 같다. */
    public static final Set<SubstituteStatus> ACTIVE = Set.of(WAITING, PENDING_MANAGER);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
