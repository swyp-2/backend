package com.baton.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Instant;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void 신규_회원은_동의_이름_역할_매장_순서로_진행한다() {
        User user = User.signUp();
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.CONSENT_REQUIRED);

        user.agreeConsents();
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.NAME_REQUIRED);

        user.setName(" 김도연 ");
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.ROLE_REQUIRED);
        assertThat(user.getDisplayName()).isEqualTo("김도연");

        user.selectRole(Role.MANAGER);
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.STORE_REQUIRED);
        assertThat(user.getSelectedRole()).isEqualTo(Role.MANAGER);

        user.joinStore();
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.COMPLETED);
    }

    @Test
    void 매장을_나가면_동의_이름_역할은_유지하고_매장_연결_단계로_돌아간다() {
        User user = at(OnboardingStage.COMPLETED);

        user.leaveStore();

        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.STORE_REQUIRED);
        assertThat(user.getDisplayName()).isEqualTo("김도연");
        assertThat(user.getSelectedRole()).isEqualTo(Role.CREW);
    }

    @Test
    void 동의는_CONSENT_REQUIRED에서만() {
        assertRejectedExcept(OnboardingStage.CONSENT_REQUIRED, User::agreeConsents);
    }

    @Test
    void 이름은_NAME_REQUIRED에서만() {
        assertRejectedExcept(OnboardingStage.NAME_REQUIRED, user -> user.setName("박소율"));
    }

    @Test
    void 역할은_ROLE_REQUIRED에서만_STORE_REQUIRED_재선택도_거부() {
        assertRejectedExcept(OnboardingStage.ROLE_REQUIRED, user -> user.selectRole(Role.CREW));
    }

    @Test
    void 매장_연결은_STORE_REQUIRED에서만() {
        assertRejectedExcept(OnboardingStage.STORE_REQUIRED, User::joinStore);
    }

    @Test
    void 매장_나가기_전이는_COMPLETED에서만() {
        assertRejectedExcept(OnboardingStage.COMPLETED, User::leaveStore);
    }

    @Test
    void 잘못된_단계에서는_이름이_유효하지_않아도_단계_충돌이_먼저다() {
        User user = User.signUp();

        assertConflict(() -> user.setName("김"), OnboardingStage.CONSENT_REQUIRED);
        assertThat(user.getDisplayName()).isNull();
    }

    @Test
    void 유효하지_않은_이름은_단계를_바꾸지_않는다() {
        User user = at(OnboardingStage.NAME_REQUIRED);

        assertThatThrownBy(() -> user.setName("김"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.NAME_REQUIRED);
        assertThat(user.getDisplayName()).isNull();
    }

    @Test
    void 탈퇴하면_이름과_역할을_지우고_WITHDRAWN이_된다() {
        User user = User.signUp();
        user.agreeConsents();
        user.setName("김도연");
        user.selectRole(Role.CREW);
        Instant now = Instant.parse("2026-11-11T03:00:00Z");

        user.withdraw(now);

        assertThat(user.getDisplayName()).isNull();
        assertThat(user.getSelectedRole()).isNull();
        assertThat(user.getAccountState()).isEqualTo(AccountState.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isEqualTo(now);
    }

    @Test
    void 이미_탈퇴한_계정은_탈퇴_시각을_바꾸지_않는다() {
        User user = User.signUp();
        Instant first = Instant.parse("2026-11-11T03:00:00Z");
        user.withdraw(first);

        user.withdraw(first.plusSeconds(60));

        assertThat(user.getWithdrawnAt()).isEqualTo(first);
    }

    private static void assertRejectedExcept(OnboardingStage allowed, Consumer<User> transition) {
        for (OnboardingStage stage : OnboardingStage.values()) {
            if (stage == allowed) {
                continue;
            }
            User user = at(stage);
            assertConflict(() -> transition.accept(user), stage);
            assertThat(user.getOnboardingStage()).isEqualTo(stage);
        }
    }

    private static void assertConflict(ThrowingCallable call, OnboardingStage currentStage) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ONBOARDING_STAGE_CONFLICT);
                    assertThat(be.getCurrentState()).isEqualTo(currentStage.name());
                });
    }

    /** 공개 전이 메서드만으로 원하는 단계의 사용자를 만든다. */
    private static User at(OnboardingStage stage) {
        User user = User.signUp();
        if (stage == OnboardingStage.CONSENT_REQUIRED) {
            return user;
        }
        user.agreeConsents();
        if (stage == OnboardingStage.NAME_REQUIRED) {
            return user;
        }
        user.setName("김도연");
        if (stage == OnboardingStage.ROLE_REQUIRED) {
            return user;
        }
        user.selectRole(Role.CREW);
        if (stage == OnboardingStage.STORE_REQUIRED) {
            return user;
        }
        user.joinStore();
        return user;
    }
}
