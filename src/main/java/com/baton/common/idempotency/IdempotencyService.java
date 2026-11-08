package com.baton.common.idempotency;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key 헤더 기반 중복 제출 방지.
 *
 * <pre>{@code
 * @PostMapping("/substitute-requests/{requestId}/accept")
 * ApiResponse<AcceptResponse> accept(@CurrentUser AuthUser user, @PathVariable long requestId,
 *         @RequestHeader(name = IdempotencyService.HEADER, required = false) String key) {
 *     AcceptResponse result = idempotencyService.execute(
 *             new IdempotencyCommand(user.userId(), IdempotencyScope.SUBSTITUTE_REQUEST_ACCEPT, key, requestId),
 *             AcceptResponse.class,
 *             () -> substituteService.accept(user.userId(), requestId));
 *     return ApiResponse.ok(result, clock);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>키 선점과 action이 한 트랜잭션이다. action이 예외로 끝나면 기록도 롤백되어 같은 키로 다시 시도할 수 있다.</li>
 *   <li>같은 키가 동시에 오면 뒤 요청은 앞 요청이 끝날 때까지 기다렸다가 같은 결과를 받는다.</li>
 *   <li>같은 키에 다른 요청 내용이면 IDEMPOTENCY_KEY_REUSED(422).</li>
 *   <li>잠금 순서: idempotency_records → membership → shift → substitute_request.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final IdempotencyRecordRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public <T> T execute(IdempotencyCommand command, Class<T> resultType, Supplier<T> action) {
        validateKey(command.key());
        String scope = command.scope().name();
        String fingerprint = fingerprint(command.scope(), command.payload());
        Instant now = clock.instant();

        if (repository.claim(command.userId(), scope, command.key(), fingerprint, now, now.plus(RETENTION)) == 0) {
            return replay(command, fingerprint, resultType);
        }
        T result = action.get();
        repository.saveResponse(command.userId(), scope, command.key(), jsonMapper.writeValueAsString(result));
        return result;
    }

    /** 탈퇴 시 해당 사용자의 기록(응답 본문 포함)을 지운다. 호출자 트랜잭션에 참여한다. */
    @Transactional
    public int purgeUser(long userId) {
        return repository.deleteByUserId(userId);
    }

    private <T> T replay(IdempotencyCommand command, String fingerprint, Class<T> resultType) {
        IdempotencyRecord record = repository
                .findByUserIdAndScopeAndIdempotencyKey(command.userId(), command.scope(), command.key())
                .orElseThrow(() -> new IllegalStateException("선점에 실패했는데 멱등성 기록이 없음"));
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return jsonMapper.readValue(record.getResponseBody(), resultType);
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(
                    new FieldError(HEADER, "영문·숫자·-·_ 1~100자로 보내 주세요.")));
        }
    }

    String fingerprint(IdempotencyScope scope, Object payload) {
        String source = scope.name() + ":" + jsonMapper.writeValueAsString(payload);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
