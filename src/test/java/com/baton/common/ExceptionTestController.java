package com.baton.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GlobalExceptionHandler 테스트 전용 컨트롤러. 테스트 소스에만 존재한다.
 */
@RestController
@RequestMapping("/test-errors")
@RequiredArgsConstructor
class ExceptionTestController {

    private final Clock clock;

    @GetMapping("/business")
    void business() {
        throw BusinessException.withCurrentState(ErrorCode.REQUEST_STATE_CONFLICT, "PENDING_MANAGER");
    }

    @PostMapping("/validation")
    ApiResponse<SampleRequest> validation(@Valid @RequestBody SampleRequest request) {
        return ApiResponse.ok(request, clock);
    }

    @GetMapping("/param")
    void param(@RequestParam @Min(1) int pageSize) {
    }

    @GetMapping("/optimistic-lock")
    void optimisticLock() {
        throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
    }

    @GetMapping("/header")
    void header(@RequestHeader("Idempotency-Key") String key) {
    }

    @GetMapping("/unexpected")
    void unexpected() {
        throw new IllegalStateException("db password=secret");
    }

    record SampleRequest(@NotBlank String storeName, @Min(1) int maxCount) {
    }
}
