package com.baton.member.domain;

import com.baton.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
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

    /** 소셜 인증 직후 생성되는 가입 진행 중 계정. */
    public static User signUp() {
        User user = new User();
        user.onboardingStage = OnboardingStage.CONSENT_REQUIRED;
        user.accountState = AccountState.ACTIVE;
        return user;
    }
}
