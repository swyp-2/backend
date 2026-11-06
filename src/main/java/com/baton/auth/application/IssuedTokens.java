package com.baton.auth.application;

import com.baton.member.domain.OnboardingStage;

public record IssuedTokens(String accessToken, String refreshToken, long expiresIn, OnboardingStage onboardingStage) {
}
