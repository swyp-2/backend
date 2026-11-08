package com.baton.auth.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * 요청 인증용. 세션이 폐기·만료되지 않았고 계정이 ACTIVE인지 한 번의 쿼리로 확인한다.
     */
    @Query("""
            select count(s) > 0
            from AuthSession s, User u
            where u.id = s.userId
              and s.id = :sessionId
              and s.userId = :userId
              and s.revokedAt is null
              and s.expiresAt > :now
              and u.accountState = com.baton.member.domain.AccountState.ACTIVE
            """)
    boolean existsUsable(@Param("sessionId") long sessionId, @Param("userId") long userId,
                         @Param("now") Instant now);

    /**
     * 아직 폐기되지 않은 경우에만 폐기한다. 동시에 같은 refresh 토큰을 쓰면 한 쪽만 1을 받는다.
     */
    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
    int revokeIfActive(@Param("id") long id, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") long userId, @Param("now") Instant now);
}
