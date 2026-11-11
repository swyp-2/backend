package com.baton.integration;

import java.util.List;

/**
 * A가 구현, B가 호출. 대타 처리 결과 알림의 "소속 매니저" 수신자 계산용. (2026-11-10 B 추가, A 구현 요청)
 *
 * <p>A 구현이 없으면 {@code integration.stub.StoreManagerQueryStub}이 쓰인다.
 */
public interface StoreManagerQuery {

    /** 매장의 ACTIVE MANAGER 소속, id 오름차순. 잠그지 않는다. 없으면 빈 목록. */
    List<ActiveMembership> findActiveManagers(long storeId);
}
