package com.baton.substitute.application;

/**
 * 조회자와 요청의 관계. 한 사람이 여러 관계에 해당하면 위에 있는 것이 우선한다
 * (후보는 지정 대상이기도 하지만 CANDIDATE로 본다).
 */
public enum RequestRelation {
    REQUESTER,
    CANDIDATE,
    TARGET,
    MANAGER
}
