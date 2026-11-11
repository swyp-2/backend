package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.shift.application.ShiftCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "근무")
@RestController
@RequiredArgsConstructor
public class ShiftCommandController {

    private final ShiftCommandService shiftCommandService;
    private final Clock clock;

    /**
     * SCH-07: 매니저의 근무 한 건 삭제(과거 근무 포함). 진행 중 대타 요청도 함께 종료한다.
     * <ul>
     *   <li>expected_version 누락·형식 오류: 422 VALIDATION_FAILED</li>
     *   <li>매니저 아님·다른 매장: 403 / 없는 근무: 404</li>
     *   <li>이미 삭제됨(current_state=DELETED), 버전·담당자가 바뀜(current_state=CHANGED): 409 SHIFT_STATE_CONFLICT.
     *       프론트는 최신 근무를 다시 조회한다</li>
     * </ul>
     */
    @Operation(summary = "근무 한 건 삭제", description = """
            SCH-07 / SH-02

            매니저가 근무 한 건을 삭제한다(과거 근무 포함). 진행 중인 대타 요청도 함께 종료하고 기록을 남긴다.
            반복 등록한 다른 날짜 근무에는 영향이 없다. 근무 상세에서 받은 version을 expected_version으로 보낸다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 근무 매장의 매니저가 아님
            - 404 NOT_FOUND: 없는 근무
            - 409 SHIFT_STATE_CONFLICT: 이미 삭제됨(current_state=DELETED), 버전·담당자가 바뀜(current_state=CHANGED).
              최신 근무를 다시 조회한다
            - 422 VALIDATION_FAILED: expected_version 누락·형식 오류(field: expected_version),
              shiftId 형식 오류(field: shift_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "매니저 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는 근무",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 삭제됨·버전 변경. current_state에 DELETED 또는 CHANGED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "expected_version 누락·형식 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/shifts/{shiftId}")
    public ApiResponse<ShiftDeleteResponse> delete(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "근무 id", example = "100") @PathVariable long shiftId,
            @Parameter(description = "근무 상세(GET /shifts/{shiftId})에서 받은 version. 다르면 409", required = true,
                    example = "0")
            @RequestParam("expected_version") long expectedVersion) {
        return ApiResponse.ok(ShiftDeleteResponse.from(
                shiftCommandService.delete(authUser.userId(), shiftId, expectedVersion)), clock);
    }
}
