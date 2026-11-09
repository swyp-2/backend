package com.baton.substitute.domain;

/**
 * 사용자의 직접 행동이 아닌 이유로 요청이 종료된 경우의 사유. 직접 취소·철회 등은 사유 없음(null).
 */
public enum TerminalReason {
    /** 요청자 소속 해제 → CANCELLED */
    REQUESTER_LEFT,
    /** 승인 대기 후보 소속 해제 → WITHDRAWN */
    CANDIDATE_LEFT,
    /** 소속 해제로 응답 가능한 지정 대상 0명 → CANCELLED */
    NO_ACTIVE_TARGET,
    /** 원본 근무 삭제 → CANCELLED_SOURCE_CHANGED */
    SHIFT_DELETED
}
