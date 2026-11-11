package com.baton.member.api;

import com.baton.member.application.MeView;
import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /me 및 가입 단계 저장 API의 응답. 본인 정보만 담고 이메일·소셜 식별자·토큰은 포함하지 않는다.
 *
 * @param displayName  이름 입력 전이면 null
 * @param selectedRole 역할 선택 전이면 null. 매장 권한 판정에 쓰지 않는다(권한은 membership.role)
 * @param membership   활성 소속이 없으면 null
 * @param nextScreen   CO-02 | CO-04 | CO-05 | ST-01 | ST-03 | CA-01
 */
public record MeResponse(
        @Schema(description = "회원 id", example = "1")
        long userId,
        @Schema(description = "가입 이름. 이름 입력 전이면 null", example = "김도연")
        String displayName,
        @Schema(description = "가입 단계", example = "COMPLETED")
        OnboardingStage onboardingStage,
        @Schema(description = "가입 때 고른 역할. 선택 전이면 null. 매장 권한 판정에 쓰지 않는다(권한은 membership.role)",
                example = "CREW")
        Role selectedRole,
        @Schema(description = "현재 활성 소속. 없으면 null")
        MembershipSummary membership,
        @Schema(description = "앱 진입 시 이동할 화면 ID", example = "CA-01",
                allowableValues = {"CO-02", "CO-04", "CO-05", "ST-01", "ST-03", "CA-01"})
        String nextScreen
) {

    public record MembershipSummary(
            @Schema(description = "소속 id", example = "10")
            long membershipId,
            @Schema(description = "매장 id", example = "1")
            long storeId,
            @Schema(description = "매장명", example = "바톤 카페")
            String storeName,
            @Schema(description = "매장 안 역할(권한 판정 기준)", example = "CREW")
            Role role,
            @Schema(description = "개인 색상(#RRGGBB 대문자)", example = "#E5484D")
            String personalColor
    ) {
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
