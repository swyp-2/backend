package com.baton.auth.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

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

    /** 아직 폐기되지 않은 경우에만 폐기한다. 이미 폐기된 세션은 최초 폐기 시각을 유지한다. */
    @Modifying
    @Query("update AuthSession s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
    int revokeIfActive(@Param("id") long id, @Param("now") Instant now);

    /** 탈퇴: 본인 세션 전부 삭제(폐기 기록도 남기지 않는다). */
    @Modifying
    @Query("delete from AuthSession s where s.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
