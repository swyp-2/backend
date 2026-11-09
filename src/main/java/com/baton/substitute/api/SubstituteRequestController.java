package com.baton.substitute.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.common.idempotency.IdempotencyCommand;
import com.baton.common.idempotency.IdempotencyScope;
import com.baton.common.idempotency.IdempotencyService;
import com.baton.substitute.application.RequestBox;
import com.baton.substitute.application.SubstituteRequestCommandService;
import com.baton.substitute.application.SubstituteRequestQueryService;
import com.baton.substitute.application.SubstituteRequestViews.Detail;
import com.baton.substitute.application.SubstituteRequestViews.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "대타 요청", description = "대타 요청 생성·상세·보낸/받은 목록")
@RestController
@RequiredArgsConstructor
public class SubstituteRequestController {

    private final SubstituteRequestCommandService commandService;
    private final SubstituteRequestQueryService queryService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    /**
     * RQ-01 → SUB-02: 대타 요청 전송. Idempotency-Key 필수(같은 키 재요청은 처음 결과 그대로).
     * 응답은 만든 요청의 상세(프론트는 보낸 요청 상세로 이동). 진행 중 요청이 이미 있으면 409 + existing_request_id.
     */
    @Operation(summary = "대타 요청 보내기 (RQ-01)", description = """
            본인 담당·시작 전 근무에 대타 요청을 만들고 지정한 동료에게 새 요청 알림을 보낸다.
            응답은 만든 요청의 상세다. 같은 Idempotency-Key로 다시 보내면 처음 결과를 그대로 돌려준다.

            - 403 FORBIDDEN: 매장 소속이 아님 / 매니저 / 본인 담당 근무가 아님
            - 404 NOT_FOUND: 없는 근무
            - 409 SHIFT_STATE_CONFLICT: 삭제된 근무
            - 409 REQUEST_EXPIRED: 이미 시작한 근무
            - 409 ACTIVE_REQUEST_EXISTS: 같은 근무에 진행 중 요청이 있음. existing_request_id의 상세로 이동한다
            - 422 VALIDATION_FAILED: 대상 0명·중복·본인·매니저·다른 매장(field: target_membership_ids), 본문 누락
            - 422 IDEMPOTENCY_KEY_REUSED: 같은 키를 다른 내용으로 재사용
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "생성된 요청 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 근무",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "근무·요청 상태 충돌",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/substitute-requests")
    public ApiResponse<Detail> create(
            @CurrentUser AuthUser authUser,
            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "중복 제출 방지 키. 영문·숫자·-·_ 1~100자. 재시도할 때는 같은 키를 다시 보낸다")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateSubstituteRequest request) {
        Detail result = idempotencyService.execute(
                new IdempotencyCommand(authUser.userId(), IdempotencyScope.SUBSTITUTE_REQUEST_CREATE, idempotencyKey,
                        new CreatePayload(request.shiftId(), request.targetMembershipIds())),
                Detail.class,
                () -> {
                    long requestId = commandService.create(authUser.userId(), request.shiftId(),
                            request.targetMembershipIds());
                    return queryService.getDetail(authUser.userId(), requestId);
                });
        return ApiResponse.ok(result, clock);
    }

    /** RQ-03 / AP-02: 요청 상세. 요청자·지정 대상·소속 매니저만. */
    @Operation(summary = "대타 요청 상세 (RQ-03 / AP-02)", description = """
            같은 매장의 요청자·지정 대상·매니저만 볼 수 있다. 조회는 상태를 바꾸지 않는다.
            allowed_actions는 표시용이고 실제 변경 API가 다시 검사한다.
            recipients는 요청자·매니저에게 전체, 지정 대상·후보에게는 본인 것만 나온다.

            - 403 FORBIDDEN: 다른 매장 / 관계없는 크루원
            - 404 NOT_FOUND: 없는 요청
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 요청",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/substitute-requests/{requestId}")
    public ApiResponse<Detail> get(@CurrentUser AuthUser authUser, @PathVariable long requestId) {
        return ApiResponse.ok(queryService.getDetail(authUser.userId(), requestId), clock);
    }

    /** RQ-02: 보낸(box=sent)·받은(box=received) 요청 목록. 20개씩, next_cursor로 다음 페이지. 크루원만. */
    @Operation(summary = "보낸·받은 대타 요청 목록 (RQ-02)", description = """
            현재 소속 매장에서 본인이 보낸(box=sent) 또는 받은(box=received) 요청. 크루원만.
            요청 생성 시각·id 내림차순 20개씩. 다음 페이지는 응답의 next_cursor를 cursor로 보낸다.

            - 403 FORBIDDEN: 매니저 / 활성 소속 없음
            - 422 VALIDATION_FAILED: box 누락·잘못된 값, cursor 형식 오류
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me/substitute-requests")
    public ApiResponse<Page> list(@CurrentUser AuthUser authUser,
                                  @Parameter(description = "sent(보낸 요청) 또는 received(받은 요청)", required = true,
                                          schema = @Schema(allowableValues = {"sent", "received"}))
                                  @RequestParam(required = false) String box,
                                  @Parameter(description = "이전 응답의 next_cursor. 첫 페이지는 생략")
                                  @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(queryService.list(authUser.userId(), RequestBox.parse(box), cursor), clock);
    }

    /** 멱등성 지문용 요청 내용(필드 순서 고정). */
    record CreatePayload(Long shiftId, List<Long> targetMembershipIds) {
    }
}
