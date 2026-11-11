package com.baton.member.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PUT /me/onboarding/role. MANAGER/CREW 외 값이 field "role"의 VALIDATION_FAILED가 되도록 문자열로 받는다.
 */
public record RoleRequest(
        @Schema(description = "가입 역할", example = "CREW", allowableValues = {"MANAGER", "CREW"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String role
) {
}
