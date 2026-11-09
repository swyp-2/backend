package com.baton.integration;

import java.time.Instant;

/**
 * 근무 한 건의 현재 상태. 대타 요청 생성 검증과 요청 스냅샷용.
 *
 * @param assigneeMembershipId 현재 담당자 소속 id
 * @param startsAt             근무 시작(UTC). 날짜·요일 계산은 Asia/Seoul 기준으로 호출자가 한다
 * @param endsAt               근무 종료(UTC). 자정을 넘는 근무면 다음 날 시각이다
 * @param version              shifts.version. {@link ShiftAssignment#reassign}의 expectedVersion으로 넘긴다
 * @param deleted              삭제된 근무면 true. 삭제된 근무도 스냅샷은 돌려준다
 */
public record ShiftSnapshot(long shiftId, long storeId, long assigneeMembershipId,
                            Instant startsAt, Instant endsAt, long version, boolean deleted) {
}
