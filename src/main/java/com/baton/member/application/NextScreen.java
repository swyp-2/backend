package com.baton.member.application;

import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;

/**
 * 앱 진입 시 이동할 화면(COM-01, 정책 D02). 서버가 회원 상태로 판정한다.
 */
public enum NextScreen {
    CO_02("CO-02"),
    CO_04("CO-04"),
    CO_05("CO-05"),
    ST_01("ST-01"),
    ST_03("ST-03"),
    CA_01("CA-01");

    private final String screenId;

    NextScreen(String screenId) {
        this.screenId = screenId;
    }

    public String screenId() {
        return screenId;
    }

    /**
     * 활성 소속이 있으면 가입 절차를 반복하지 않고 근무표(CA-01).
     * 소속이 없으면 저장된 단계의 다음 화면. 매장 연결 단계(또는 매장을 나간 뒤)는 동의·이름을 다시 묻지 않고
     * 저장된 역할 기준 ST-01/ST-03, 역할도 없으면 CO-05.
     */
    public static NextScreen of(boolean hasActiveMembership, OnboardingStage stage, Role selectedRole) {
        if (hasActiveMembership) {
            return CA_01;
        }
        return switch (stage) {
            case CONSENT_REQUIRED -> CO_02;
            case NAME_REQUIRED -> CO_04;
            case ROLE_REQUIRED -> CO_05;
            case STORE_REQUIRED, COMPLETED -> {
                if (selectedRole == null) {
                    yield CO_05;
                }
                yield switch (selectedRole) {
                    case MANAGER -> ST_01;
                    case CREW -> ST_03;
                };
            }
        };
    }
}
