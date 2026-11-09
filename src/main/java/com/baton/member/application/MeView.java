package com.baton.member.application;

import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;

/**
 * 본인 회원·가입 단계·현재 소속. 이메일·소셜 식별자는 담지 않는다.
 */
public record MeView(
        long userId,
        String displayName,
        OnboardingStage onboardingStage,
        Role selectedRole,
        MembershipView membership,
        NextScreen nextScreen
) {

    public record MembershipView(long membershipId, long storeId, String storeName, Role role,
                                 String personalColor) {
    }
}
