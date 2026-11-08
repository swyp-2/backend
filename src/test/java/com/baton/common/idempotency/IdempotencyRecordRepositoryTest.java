package com.baton.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.support.RepositoryTest;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@RepositoryTest
class IdempotencyRecordRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final Duration TTL = IdempotencyService.RETENTION;
    private static final String SCOPE = IdempotencyScope.SUBSTITUTE_REQUEST_APPROVE.name();
    private static final String FP_A = "a".repeat(64);
    private static final String FP_B = "b".repeat(64);

    @Autowired IdempotencyRecordRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void 새_키는_선점하고_유효한_기록이_있으면_선점하지_못한다() {
        assertThat(repository.claim(1L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL))).isEqualTo(1);
        assertThat(repository.claim(1L, SCOPE, "k1", FP_B, NOW.plusSeconds(60), NOW.plus(TTL))).isZero();

        assertThat(find(1L, "k1").getRequestFingerprint()).isEqualTo(FP_A);
    }

    @Test
    void 만료된_기록은_새_요청이_덮어쓴다() {
        repository.claim(1L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL));
        repository.saveResponse(1L, SCOPE, "k1", "{\"ok\": true}");

        Instant later = NOW.plus(TTL);
        assertThat(repository.claim(1L, SCOPE, "k1", FP_B, later, later.plus(TTL))).isEqualTo(1);

        IdempotencyRecord record = find(1L, "k1");
        assertThat(record.getRequestFingerprint()).isEqualTo(FP_B);
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getExpiresAt()).isEqualTo(later.plus(TTL));
    }

    @Test
    void 사용자나_작업_종류가_다르면_같은_키도_별개다() {
        assertThat(repository.claim(1L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL))).isEqualTo(1);
        assertThat(repository.claim(2L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL))).isEqualTo(1);
        assertThat(repository.claim(1L, IdempotencyScope.SUBSTITUTE_REQUEST_CANCEL.name(), "k1", FP_A, NOW,
                NOW.plus(TTL))).isEqualTo(1);
    }

    @Test
    void 응답_본문을_jsonb로_저장하고_읽는다() {
        repository.claim(1L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL));
        repository.saveResponse(1L, SCOPE, "k1", "{\"request_id\": 7, \"status\": \"APPROVED\"}");

        assertThat(find(1L, "k1").getResponseBody()).contains("\"request_id\"").contains("APPROVED");
    }

    @Test
    void 만료_정리는_만료된_기록만_지운다() {
        repository.claim(1L, SCOPE, "old", FP_A, NOW, NOW.plus(TTL));
        repository.claim(1L, SCOPE, "new", FP_A, NOW.plusSeconds(3600), NOW.plusSeconds(3600).plus(TTL));

        assertThat(repository.deleteExpired(NOW.plus(TTL))).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findByUserIdAndScopeAndIdempotencyKey(1L, IdempotencyScope.SUBSTITUTE_REQUEST_APPROVE,
                "old")).isEmpty();
        assertThat(repository.findByUserIdAndScopeAndIdempotencyKey(1L, IdempotencyScope.SUBSTITUTE_REQUEST_APPROVE,
                "new")).isPresent();
    }

    @Test
    void 탈퇴_정리는_해당_사용자_기록만_지운다() {
        repository.claim(1L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL));
        repository.claim(2L, SCOPE, "k1", FP_A, NOW, NOW.plus(TTL));

        assertThat(repository.deleteByUserId(1L)).isEqualTo(1);
        entityManager.clear();
        assertThat(repository.findAll()).extracting(IdempotencyRecord::getUserId).containsExactly(2L);
    }

    @Test
    void DB_제약이_알_수_없는_작업_종류를_막는다() {
        assertThatThrownBy(() -> repository.claim(1L, "UNKNOWN_SCOPE", "k1", FP_A, NOW, NOW.plus(TTL)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void DB_제약이_키_형식을_막는다() {
        assertThatThrownBy(() -> repository.claim(1L, SCOPE, "has space", FP_A, NOW, NOW.plus(TTL)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private IdempotencyRecord find(long userId, String key) {
        entityManager.clear();
        return repository.findByUserIdAndScopeAndIdempotencyKey(
                userId, IdempotencyScope.SUBSTITUTE_REQUEST_APPROVE, key).orElseThrow();
    }
}
