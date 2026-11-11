package com.baton.member.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PUT /me/onboarding/name. 검증(앞뒤 공백 제거 후 2~15자, 제어문자 거부)은 DisplayName이 한다.
 */
public record NameRequest(
        @Schema(description = "가입 이름. 앞뒤 공백 제거 후 2~15자, 줄바꿈·제어문자 불가", example = "김도연",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName
) {
}
