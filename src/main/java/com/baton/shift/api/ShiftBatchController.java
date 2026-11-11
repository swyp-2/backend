package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.common.idempotency.IdempotencyCommand;
import com.baton.common.idempotency.IdempotencyScope;
import com.baton.common.idempotency.IdempotencyService;
import com.baton.shift.api.ShiftBatchRequests.CreateRequest;
import com.baton.shift.api.ShiftBatchRequests.PreviewRequest;
import com.baton.shift.api.ShiftBatchResponses.CreateResponse;
import com.baton.shift.api.ShiftBatchResponses.PreviewResponse;
import com.baton.shift.application.ShiftBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "근무")
@RestController
@RequiredArgsConstructor
public class ShiftBatchController {

    private final ShiftBatchService shiftBatchService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    /** ED-01~03: 생성 계획·완전 동일 중복 표시·미리보기 토큰(30분). 근무를 저장하지 않는다. */
    @Operation(summary = "근무 일괄 등록 미리보기", description = """
            SCH-03, SCH-04, SCH-05 / ED-01, ED-02, ED-03

            입력으로 만들 근무 계획을 계산하고 이미 있는 완전 동일 근무(같은 담당자·시작·종료)를 duplicate=true로 표시한다.
            근무를 저장하지 않는다. 최종 등록에는 같은 입력과 함께 preview_token(30분 유효)을 보낸다.
            반복이면 시작일~종료일(달력상 최대 1개월, 양 끝 포함)의 선택 요일마다 같은 시각으로 날짜별 근무를 만든다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 매니저가 아님
            - 422 VALIDATION_FAILED: store_id 누락, 근무자 0명·중복·null·현재 구성원 아님(field: assignee_membership_ids),
              시작·종료 누락·분 단위 아님·길이 0 이하 또는 24시간 이상(field: starts_at, ends_at),
              반복 시작일이 첫 근무 날짜와 다름·종료일 역전·1개월 초과(field: repeat.start_date, repeat.end_date),
              요일 0개·1~7 밖·기간 안에 선택 요일 없음(field: repeat.weekdays), 계획이 2000건 초과,
              본문을 읽을 수 없음(field: body)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "미리보기"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "매니저 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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
    @Operation(summary = "근무 일괄 등록", description = """
            SCH-05 / ED-03

            미리보기와 같은 입력 + preview_token으로 최종 저장한다. 서버가 권한·계획·구성원·중복을 다시 계산하고,
            완전 동일 중복을 뺀 새 근무만 전부 저장하거나 하나도 저장하지 않는다.
            같은 Idempotency-Key·같은 본문으로 다시 보내면 처음 결과를 그대로 돌려준다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 매니저가 아님
            - 409 PREVIEW_STALE: 토큰 만료(30분)·변조·다른 매장/매니저/입력의 토큰, 미리보기 이후 근무표가 바뀜,
              저장 중 같은 근무가 먼저 등록됨. 입력을 유지하고 미리보기를 다시 요청한다
            - 422 VALIDATION_FAILED: Idempotency-Key 누락·형식 오류(field: Idempotency-Key),
              preview_token 누락(field: preview_token), 모두 이미 등록된 근무(field_errors 없음, message로 안내),
              그 밖의 입력 오류는 미리보기와 같음
            - 422 IDEMPOTENCY_KEY_REUSED: 같은 키를 다른 본문으로 재사용
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "매니저 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미리보기 이후 변경·토큰 만료",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "입력 오류·Idempotency-Key 오류·재사용",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/shift-batches")
    public ApiResponse<CreateResponse> create(
            @CurrentUser AuthUser authUser,
            @Parameter(in = ParameterIn.HEADER, required = true, example = "shift-batch-7c2e90",
                    description = "중복 제출 방지 키. 영문·숫자·-·_ 1~100자. 재시도할 때는 같은 키를 다시 보낸다")
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
