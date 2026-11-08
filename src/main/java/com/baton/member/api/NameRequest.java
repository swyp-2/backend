package com.baton.member.api;

/**
 * PUT /me/onboarding/name. 검증(앞뒤 공백 제거 후 2~15자, 제어문자 거부)은 DisplayName이 한다.
 */
public record NameRequest(String displayName) {
}
