package com.baton.auth.api;

import com.baton.auth.application.IssuedTokens;
import com.baton.member.domain.OnboardingStage;

/**
 * @param expiresIn 액세스 토큰 수명(초). 만료되면 다시 소셜 로그인한다(refresh 없음).
 */
public record TokenResponse(String accessToken, long expiresIn, OnboardingStage onboardingStage) {

    static TokenResponse from(IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.expiresIn(), tokens.onboardingStage());
    }
}
