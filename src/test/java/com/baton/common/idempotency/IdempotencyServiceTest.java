package com.baton.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    private static final IdempotencyScope SCOPE = IdempotencyScope.SUBSTITUTE_REQUEST_ACCEPT;

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final IdempotencyService service =
            new IdempotencyService(repository, jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AtomicInteger executions = new AtomicInteger();

    @Test
    void 선점하면_실행하고_결과를_저장한다_보존은_24시간() {
        String fp = service.fingerprint(SCOPE, new Payload(7L));
        when(repository.claim(1L, SCOPE.name(), "key-1", fp, NOW, NOW.plusSeconds(24 * 3600))).thenReturn(1);

        Result result = service.execute(command("key-1", 7L), Result.class, this::action);

        assertThat(result).isEqualTo(new Result(7L, 1));
        verify(repository).saveResponse(1L, SCOPE.name(), "key-1", "{\"request_id\":7,\"execution_no\":1}");
    }

    @Test
    void 선점하지_못하고_요청이_같으면_실행하지_않고_저장된_결과를_돌려준다() {
        String fp = service.fingerprint(SCOPE, new Payload(7L));
        when(repository.claim(eq(1L), anyString(), anyString(), anyString(), eq(NOW), eq(NOW.plus(IdempotencyService.RETENTION))))
                .thenReturn(0);
        IdempotencyRecord saved = record(fp, "{\"request_id\":7,\"execution_no\":1}");
        when(repository.findByUserIdAndScopeAndIdempotencyKey(1L, SCOPE, "key-1")).thenReturn(Optional.of(saved));

        Result result = service.execute(command("key-1", 7L), Result.class, this::action);

        assertThat(result).isEqualTo(new Result(7L, 1));
        assertThat(executions).hasValue(0);
        verify(repository, never()).saveResponse(eq(1L), anyString(), anyString(), anyString());
    }

    @Test
    void 선점하지_못하고_요청이_다르면_IDEMPOTENCY_KEY_REUSED() {
        when(repository.claim(eq(1L), anyString(), anyString(), anyString(), eq(NOW), eq(NOW.plus(IdempotencyService.RETENTION))))
                .thenReturn(0);
        IdempotencyRecord saved = record(service.fingerprint(SCOPE, new Payload(8L)), "{}");
        when(repository.findByUserIdAndScopeAndIdempotencyKey(1L, SCOPE, "key-1")).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.execute(command("key-1", 7L), Result.class, this::action))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(executions).hasValue(0);
    }

    @Test
    void 키_형식이_틀리면_DB에_가지_않고_VALIDATION_FAILED() {
        for (String invalid : new String[] {null, "", " ", "has space", "키", "a".repeat(101)}) {
            assertThatThrownBy(() -> service.execute(command(invalid, 7L), Result.class, this::action))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        verify(repository, never()).claim(eq(1L), anyString(), anyString(), anyString(), eq(NOW), eq(NOW.plus(IdempotencyService.RETENTION)));
    }

    @Test
    void 지문은_같은_요청이면_같고_내용이나_작업_종류가_다르면_다르다() {
        String base = service.fingerprint(SCOPE, new Payload(7L));

        assertThat(service.fingerprint(SCOPE, new Payload(7L))).isEqualTo(base).matches("^[0-9a-f]{64}$");
        assertThat(service.fingerprint(SCOPE, new Payload(8L))).isNotEqualTo(base);
        assertThat(service.fingerprint(IdempotencyScope.SUBSTITUTE_REQUEST_REJECT, new Payload(7L))).isNotEqualTo(base);
    }

    private Result action() {
        return new Result(7L, executions.incrementAndGet());
    }

    private static IdempotencyCommand command(String key, long requestId) {
        return new IdempotencyCommand(1L, SCOPE, key, new Payload(requestId));
    }

    private static IdempotencyRecord record(String fingerprint, String body) {
        IdempotencyRecord record = mock(IdempotencyRecord.class);
        when(record.getRequestFingerprint()).thenReturn(fingerprint);
        when(record.getResponseBody()).thenReturn(body);
        return record;
    }

    record Payload(long requestId) {
    }

    record Result(long requestId, int executionNo) {
    }
}
