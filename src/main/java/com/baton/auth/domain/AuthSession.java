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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * refresh 토큰 세션. 토큰 원문은 저장하지 않고 SHA-256 hex(64자)만 저장한다.
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

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AuthSession open(Long userId, String refreshTokenHash, Instant expiresAt, Instant now) {
        AuthSession session = new AuthSession();
        session.userId = userId;
        session.refreshTokenHash = refreshTokenHash;
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
