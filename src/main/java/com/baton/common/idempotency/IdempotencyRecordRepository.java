package com.baton.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * 키를 선점한다. 새 키이거나 기존 기록이 만료됐으면 1(선점 성공), 유효한 기록이 있으면 0.
     * 같은 키를 다른 트랜잭션이 선점 중이면 그 트랜잭션이 끝날 때까지 기다린다(PostgreSQL 유니크 검사).
     * 상대가 롤백하면 이쪽이 선점하고, 커밋하면 0을 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_records
                (user_id, scope, idempotency_key, request_fingerprint, created_at, expires_at)
            VALUES (:userId, :scope, :key, :fingerprint, :now, :expiresAt)
            ON CONFLICT (user_id, scope, idempotency_key) DO UPDATE
                SET request_fingerprint = EXCLUDED.request_fingerprint,
                    response_body       = NULL,
                    created_at          = EXCLUDED.created_at,
                    expires_at          = EXCLUDED.expires_at
                WHERE idempotency_records.expires_at <= EXCLUDED.created_at
            """, nativeQuery = true)
    int claim(@Param("userId") long userId, @Param("scope") String scope, @Param("key") String key,
              @Param("fingerprint") String fingerprint, @Param("now") Instant now,
              @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = """
            UPDATE idempotency_records SET response_body = CAST(:body AS jsonb)
            WHERE user_id = :userId AND scope = :scope AND idempotency_key = :key
            """, nativeQuery = true)
    int saveResponse(@Param("userId") long userId, @Param("scope") String scope, @Param("key") String key,
                     @Param("body") String body);

    Optional<IdempotencyRecord> findByUserIdAndScopeAndIdempotencyKey(long userId, IdempotencyScope scope,
                                                                     String idempotencyKey);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
