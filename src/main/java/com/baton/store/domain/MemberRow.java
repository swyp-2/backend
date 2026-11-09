package com.baton.store.domain;

import com.baton.member.domain.Role;

/** 매장 구성원 목록 조회용 projection. ACTIVE 소속이라 userId는 항상 있다. */
public record MemberRow(Long membershipId, Long userId, String displayName, Role role, String personalColor) {
}
