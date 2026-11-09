package com.baton.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 로그인 세션. 액세스 토큰의 sid가 이 id를 가리키며, 토큰 원문은 저장하지 않는다.
 */
@Getter
@Entity
@Table(name = "auth_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "auth_sessions_seq")
    @SequenceGenerator(name = "auth_sessions_seq", sequenceName = "auth_sessions_seq", allocationSize = 50)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AuthSession open(Long userId, Instant expiresAt, Instant now) {
        AuthSession session = new AuthSession();
        session.userId = userId;
        session.expiresAt = expiresAt;
        session.createdAt = now;
        return session;
    }

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** 이미 폐기된 세션은 최초 폐기 시각을 유지한다. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
