package com.baton.auth.api;

import com.baton.auth.application.IssuedTokens;
import com.baton.member.domain.OnboardingStage;

/**
 * @param expiresIn 액세스 토큰 남은 수명(초)
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn,
                            OnboardingStage onboardingStage) {

    static TokenResponse from(IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(),
                tokens.onboardingStage());
    }
}
