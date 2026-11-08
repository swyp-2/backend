package com.baton.common.idempotency;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IdempotencyService 테스트 전용 컨트롤러. 대상(targetId)별로 실제 실행 횟수를 센다.
 */
@RestController
@RequestMapping("/test-idempotency")
@RequiredArgsConstructor
class IdempotencyTestController {

    private final IdempotencyService idempotencyService;
    private final Clock clock;

    final Map<Long, AtomicInteger> executions = new ConcurrentHashMap<>();
    /** 여기 들어 있는 대상은 다음 1회 실행이 실패한다. */
    final Set<Long> failOnce = ConcurrentHashMap.newKeySet();
    volatile long actionDelayMillis;

    @PostMapping("/targets/{targetId}")
    ApiResponse<Result> run(@CurrentUser AuthUser user, @PathVariable long targetId,
                            @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
                            @RequestBody Body body) {
        Result result = idempotencyService.execute(
                new IdempotencyCommand(user.userId(), IdempotencyScope.SUBSTITUTE_REQUEST_ACCEPT, key,
                        new Payload(targetId, body)),
                Result.class,
                () -> {
                    sleep(actionDelayMillis);
                    int executionNo = executions.computeIfAbsent(targetId, id -> new AtomicInteger()).incrementAndGet();
                    if (failOnce.remove(targetId)) {
                        throw new BusinessException(ErrorCode.REQUEST_STATE_CONFLICT);
                    }
                    return new Result(targetId, body.amount(), executionNo, clock.instant());
                });
        return ApiResponse.ok(result, clock);
    }

    int executionCount(long targetId) {
        AtomicInteger count = executions.get(targetId);
        return count == null ? 0 : count.get();
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Body(int amount) {
    }

    record Payload(long targetId, Body body) {
    }

    record Result(long targetId, int amount, int executionNo, Instant processedAt) {
    }
}
