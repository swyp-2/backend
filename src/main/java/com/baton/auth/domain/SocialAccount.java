package com.baton.auth.domain;

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

/**
 * 소셜 인증 연결. provider_subject는 어떤 응답에도 포함하지 않는다.
 */
@Getter
@Entity
@Table(name = "social_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "social_accounts_seq")
    @SequenceGenerator(name = "social_accounts_seq", sequenceName = "social_accounts_seq", allocationSize = 50)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 10)
    private SocialProvider provider;

    @Column(name = "provider_subject", nullable = false)
    private String providerSubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SocialAccount link(Long userId, SocialProvider provider, String providerSubject, Instant now) {
        SocialAccount account = new SocialAccount();
        account.userId = userId;
        account.provider = provider;
        account.providerSubject = providerSubject;
        account.createdAt = now;
        return account;
    }
}
