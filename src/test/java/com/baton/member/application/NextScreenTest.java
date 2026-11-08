package com.baton.member.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NextScreenTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "CONSENT_REQUIRED, null,    CO-02",
            "NAME_REQUIRED,    null,    CO-04",
            "ROLE_REQUIRED,    null,    CO-05",
            "STORE_REQUIRED,   MANAGER, ST-01",
            "STORE_REQUIRED,   CREW,    ST-03",
            "STORE_REQUIRED,   null,    CO-05",
            "COMPLETED,        MANAGER, ST-01",
            "COMPLETED,        CREW,    ST-03",
            "COMPLETED,        null,    CO-05",
    })
    void 소속이_없으면_저장된_단계와_역할로_분기한다(OnboardingStage stage, Role role, String expected) {
        assertThat(NextScreen.of(false, stage, role).screenId()).isEqualTo(expected);
    }

    @Test
    void 활성_소속이_있으면_단계와_무관하게_근무표() {
        for (OnboardingStage stage : OnboardingStage.values()) {
            assertThat(NextScreen.of(true, stage, Role.CREW)).isEqualTo(NextScreen.CA_01);
        }
    }
}
