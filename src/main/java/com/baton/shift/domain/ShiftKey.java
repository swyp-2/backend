package com.baton.shift.domain;

import java.time.Instant;

/** 완전 동일 중복(B05) 판정 키. 매장은 조회 조건으로 고정되므로 담당자·시작·종료만 둔다. */
public record ShiftKey(long membershipId, Instant startsAt, Instant endsAt) {
}
