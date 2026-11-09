package com.baton.integration;

import java.util.List;
import java.util.Optional;

/**
 * A가 구현, B가 호출. 시그니처는 docs/backend-split.md 기준.
 */
public interface MembershipQuery {

    /** 활성 소속이 아니면 BusinessException(FORBIDDEN). */
    ActiveMembership requireActive(long userId, long storeId);

    /**
     * 사용자의 현재 활성 소속. 없으면 empty. MVP는 계정당 활성 매장이 하나다(memberships_user_active_uk).
     * 경로에 매장 id가 없는 본인 API(GET /me/substitute-requests 등)에서 쓴다. (2026-11-09 B 추가, A 구현 기존 메서드)
     */
    Optional<ActiveMembership> findActiveByUser(long userId);

    /** 잠금 버전(SELECT ... FOR UPDATE). 승인·수락 시 후보 소속 재확인용. 호출자 트랜잭션 안에서만 호출한다. */
    Optional<ActiveMembership> lockActiveById(long membershipId);

    /** 대타 후보: 같은 매장 ACTIVE CREW, exclude 제외. */
    List<ActiveMembership> findCrewCandidates(long storeId, long excludeMembershipId);

    /** 표시용 이름. 탈퇴/연결 해제면 "탈퇴한 구성원". */
    String displayNameOf(long membershipId);
}
