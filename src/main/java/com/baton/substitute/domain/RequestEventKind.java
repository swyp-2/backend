package com.baton.substitute.domain;

/**
 * 요청 진행 기록 종류. 알림의 kind로도 쓴다(만료·근무 삭제는 알림을 만들지 않는다).
 */
public enum RequestEventKind {
    REQUEST_CREATED,
    CANDIDATE_ACCEPTED,
    /** 개별 거절. 마지막 거절이면 after_status가 ALL_DECLINED. */
    TARGET_DECLINED,
    REQUEST_CANCELLED,
    CANDIDATE_WITHDRAWN,
    MANAGER_APPROVED,
    MANAGER_REJECTED,
    REQUEST_EXPIRED,
    SOURCE_DELETED,
    /** 요청자·후보·지정 대상의 소속 해제에 따른 정리 */
    MEMBER_LEFT;

    public boolean createsNotification() {
        return this != REQUEST_EXPIRED && this != SOURCE_DELETED;
    }
}
