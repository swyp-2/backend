package com.baton.member.domain;

import com.baton.common.BaseTimeEntity;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_seq")
    @SequenceGenerator(name = "users_seq", sequenceName = "users_seq", allocationSize = 50)
    private Long id;

    @Column(name = "display_name", length = 15)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_stage", nullable = false, length = 30)
    private OnboardingStage onboardingStage;

    @Enumerated(EnumType.STRING)
    @Column(name = "selected_role", length = 10)
    private Role selectedRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_state", nullable = false, length = 20)
    private AccountState accountState;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 소셜 인증 직후 생성되는 가입 진행 중 계정. */
    public static User signUp() {
        User user = new User();
        user.onboardingStage = OnboardingStage.CONSENT_REQUIRED;
        user.accountState = AccountState.ACTIVE;
        return user;
    }

    // ---- 가입 단계 전이. 단계는 이 메서드로만 바뀌고, 허용되지 않은 전이는 ONBOARDING_STAGE_CONFLICT. ----

    /** CO-02: 필수 약관 동의 저장(문서별 기록은 호출 측이 저장). CONSENT_REQUIRED → NAME_REQUIRED. */
    public void agreeConsents() {
        requireStage(OnboardingStage.CONSENT_REQUIRED);
        onboardingStage = OnboardingStage.NAME_REQUIRED;
    }

    /**
     * CO-04: 이름 저장({@link DisplayName#normalize} 규칙으로 검증·앞뒤 공백 제거). NAME_REQUIRED → ROLE_REQUIRED.
     * 단계 검사가 먼저다. 매장 소속은 만들지 않는다.
     */
    public void setName(String rawName) {
        requireStage(OnboardingStage.NAME_REQUIRED);
        displayName = DisplayName.normalize(rawName);
        onboardingStage = OnboardingStage.ROLE_REQUIRED;
    }

    /** CO-05: 가입 역할 저장. ROLE_REQUIRED → STORE_REQUIRED. 어떤 매장 권한도 생기지 않는다(권한은 Membership.role). */
    public void selectRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        requireStage(OnboardingStage.ROLE_REQUIRED);
        selectedRole = role;
        onboardingStage = OnboardingStage.STORE_REQUIRED;
    }

    /** ST-01/ST-03: 매장 생성·참여로 소속이 생김. STORE_REQUIRED → COMPLETED. */
    public void joinStore() {
        requireStage(OnboardingStage.STORE_REQUIRED);
        if (selectedRole == null) {
            throw stageConflict();
        }
        onboardingStage = OnboardingStage.COMPLETED;
    }

    /** 매장 나가기. 동의·이름·역할은 유지하고 매장 연결 단계로 돌아간다. COMPLETED → STORE_REQUIRED. */
    public void leaveStore() {
        requireStage(OnboardingStage.COMPLETED);
        onboardingStage = OnboardingStage.STORE_REQUIRED;
    }

    /**
     * 계정 탈퇴. 이름·선택 역할을 지우고 WITHDRAWN으로 바꾼다. 행은 개인 연결이 없는 톰스톤으로 남는다.
     * 이미 탈퇴한 계정이면 아무것도 바꾸지 않는다.
     */
    public void withdraw(Instant now) {
        if (accountState == AccountState.WITHDRAWN) {
            return;
        }
        displayName = null;
        selectedRole = null;
        accountState = AccountState.WITHDRAWN;
        withdrawnAt = now;
    }

    private void requireStage(OnboardingStage expected) {
        if (onboardingStage != expected) {
            throw stageConflict();
        }
    }

    private BusinessException stageConflict() {
        return BusinessException.withCurrentState(ErrorCode.ONBOARDING_STAGE_CONFLICT, onboardingStage.name());
    }
}
