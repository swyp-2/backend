package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.idempotency.IdempotencyCommand;
import com.baton.common.idempotency.IdempotencyScope;
import com.baton.common.idempotency.IdempotencyService;
import com.baton.shift.api.ShiftBatchRequests.CreateRequest;
import com.baton.shift.api.ShiftBatchRequests.PreviewRequest;
import com.baton.shift.api.ShiftBatchResponses.CreateResponse;
import com.baton.shift.api.ShiftBatchResponses.PreviewResponse;
import com.baton.shift.application.ShiftBatchService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShiftBatchController {

    private final ShiftBatchService shiftBatchService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    /** ED-01~03: 생성 계획·완전 동일 중복 표시·미리보기 토큰(30분). 근무를 저장하지 않는다. */
    @PostMapping("/shift-batches/preview")
    public ApiResponse<PreviewResponse> preview(@CurrentUser AuthUser authUser,
                                                @RequestBody PreviewRequest request) {
        return ApiResponse.ok(PreviewResponse.from(
                shiftBatchService.preview(authUser.userId(), request.storeId(), request.toInput())), clock);
    }

    /**
     * ED-03: 최종 일괄 등록. Idempotency-Key 필수(같은 키·같은 본문은 처음 결과 그대로).
     * 미리보기 이후 근무표가 바뀌었거나 토큰이 만료·변조됐으면 409 PREVIEW_STALE, 저장은 전부 아니면 0건.
     */
    @PostMapping("/shift-batches")
    public ApiResponse<CreateResponse> create(
            @CurrentUser AuthUser authUser,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody CreateRequest request) {
        CreateResponse result = idempotencyService.execute(
                new IdempotencyCommand(authUser.userId(), IdempotencyScope.SHIFT_BATCH_CREATE, idempotencyKey,
                        request),
                CreateResponse.class,
                () -> CreateResponse.from(shiftBatchService.create(authUser.userId(), request.storeId(),
                        request.toInput(), request.previewToken())));
        return ApiResponse.ok(result, clock);
    }
}
