package com.baton.shift.domain;

import com.baton.member.domain.AccountState;
import java.time.Instant;

/**
 * 근무 조회용 projection. 근무 + 담당 소속 + 사용자를 한 번의 조인으로 읽는다(N+1 방지).
 * 탈퇴하면 memberships.user_id가 끊기므로 assigneeUserId·displayName·accountState는 null일 수 있다.
 */
public record ShiftRow(Long shiftId, Long storeId, Long assigneeMembershipId, Instant startsAt, Instant endsAt,
                       long version, Long assigneeUserId, String displayName, AccountState accountState,
                       String personalColor) {
}
