package com.baton.member.domain;

/**
 * users.selected_role과 memberships.role이 같은 값을 쓴다. 매장 권한은 Membership.role로만 판정한다.
 */
public enum Role {
    MANAGER,
    CREW
}
