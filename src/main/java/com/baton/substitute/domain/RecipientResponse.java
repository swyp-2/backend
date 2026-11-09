package com.baton.substitute.domain;

/**
 * 지정 대상 한 명의 응답 상태.
 */
public enum RecipientResponse {
    PENDING,
    ACCEPTED,
    /** 거절. 요청이 아직 WAITING이면 재수락할 수 있다. */
    DECLINED,
    /** 소속 해제로 응답 불가. 다시 응답할 수 없다. */
    UNAVAILABLE
}
