package com.baton.substitute.domain;

/**
 * 요청 관계자 스냅샷(잠그지 않은 조회). 상태 전이에서 잠글 소속을 정하는 데만 쓴다.
 */
public record RequestParties(long id, long storeId, long shiftId, long requesterMembershipId,
                             Long candidateMembershipId) {
}
