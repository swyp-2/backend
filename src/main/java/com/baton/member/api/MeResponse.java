package com.baton.member.api;

import com.baton.member.application.MeView;
import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;

/**
 * GET /me 및 가입 단계 저장 API의 응답. 본인 정보만 담고 이메일·소셜 식별자·토큰은 포함하지 않는다.
 *
 * @param displayName  이름 입력 전이면 null
 * @param selectedRole 역할 선택 전이면 null. 매장 권한 판정에 쓰지 않는다(권한은 membership.role)
 * @param membership   활성 소속이 없으면 null
 * @param nextScreen   CO-02 | CO-04 | CO-05 | ST-01 | ST-03 | CA-01
 */
public record MeResponse(
        long userId,
        String displayName,
        OnboardingStage onboardingStage,
        Role selectedRole,
        MembershipSummary membership,
        String nextScreen
) {

    public record MembershipSummary(long membershipId, long storeId, String storeName, Role role,
                                    String personalColor) {
    }

    static MeResponse from(MeView view) {
        MeView.MembershipView m = view.membership();
        return new MeResponse(
                view.userId(),
                view.displayName(),
                view.onboardingStage(),
                view.selectedRole(),
                m == null ? null
                        : new MembershipSummary(m.membershipId(), m.storeId(), m.storeName(), m.role(),
                                m.personalColor()),
                view.nextScreen().screenId());
    }
}
