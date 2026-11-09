package com.baton.member.api;

/**
 * PUT /me/onboarding/role. MANAGER/CREW 외 값이 field "role"의 VALIDATION_FAILED가 되도록 문자열로 받는다.
 */
public record RoleRequest(String role) {
}
