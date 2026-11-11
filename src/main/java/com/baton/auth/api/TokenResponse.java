package com.baton.auth.api;

import com.baton.auth.application.IssuedTokens;
import com.baton.member.domain.OnboardingStage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param expiresIn 액세스 토큰 수명(초). 만료되면 다시 소셜 로그인한다(refresh 없음).
 */
public record TokenResponse(
        @Schema(description = "BATON 액세스 토큰. Authorization: Bearer {access_token}으로 보낸다",
                example = "access_token_example")
        String accessToken,
        @Schema(description = "액세스 토큰 수명(초). 만료되면 다시 소셜 로그인한다(refresh 없음)", example = "1209600")
        long expiresIn,
        @Schema(description = "현재 가입 단계. 다음 화면은 GET /me의 next_screen으로 판단한다", example = "CONSENT_REQUIRED")
        OnboardingStage onboardingStage
) {

    static TokenResponse from(IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.expiresIn(), tokens.onboardingStage());
    }
}
