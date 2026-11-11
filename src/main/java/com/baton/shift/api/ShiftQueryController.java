package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.ErrorResponse;
import com.baton.common.FieldError;
import com.baton.shift.application.ShiftQueryService;
import com.baton.shift.application.ShiftQueryService.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "근무", description = "근무 달력·날짜별 명단·근무 상세·일괄 등록·삭제")
@RestController
@RequiredArgsConstructor
public class ShiftQueryController {

    private final ShiftQueryService shiftQueryService;
    private final Clock clock;

    /**
     * CA-01/02: {@code ?month=yyyy-MM&scope=mine|all} 월 달력. CA-03: {@code ?date=yyyy-MM-dd} 선택일 전체 명단.
     * month와 date는 정확히 하나만. month 조회는 scope 필수, date 조회는 scope를 무시한다(항상 매장 전체).
     */
    @Operation(summary = "월 달력·선택일 명단 조회", description = """
            SCH-01 / CA-01, CA-02 (month 조회), SCH-02 / CA-03 (date 조회)

            month와 date 중 정확히 하나만 보낸다. 근무는 starts_at의 Asia/Seoul 날짜에 속한다(자정을 넘겨도 시작일).
            - month 조회(scope 필수): 근무가 있는 날의 중복 제거 인원(days). scope=mine이면 그 달 내 근무 목록(shifts)도,
              scope=all이면 shifts는 빈 배열 → ShiftMonthResponse
            - date 조회: 그날 매장 전체 근무를 시작 시각 → 이름 → membership_id 순으로. scope는 무시 → ShiftDayResponse

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 활성 소속이 아님
            - 422 VALIDATION_FAILED: month·date 둘 다 있거나 둘 다 없음(field: month, date), month가 yyyy-MM 아님,
              date가 yyyy-MM-dd 아님, month 조회인데 scope가 mine·all 아님(field: scope), storeId 형식 오류(field: store_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "month 조회면 ShiftMonthResponse, date 조회면 ShiftDayResponse",
                    content = @Content(schema = @Schema(oneOf = {MonthEnvelope.class, DayEnvelope.class}))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "조회 조건 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/stores/{storeId}/shifts")
    public ApiResponse<?> list(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "매장 id", example = "1") @PathVariable long storeId,
            @Parameter(description = "월 달력 조회(yyyy-MM, Asia/Seoul). date와 함께 보낼 수 없다", example = "2026-11")
            @RequestParam(required = false) String month,
            @Parameter(description = "선택일 명단 조회(yyyy-MM-dd, Asia/Seoul). month와 함께 보낼 수 없다",
                    example = "2026-11-11")
            @RequestParam(required = false) String date,
            @Parameter(description = "month 조회에서 필수. mine(내 근무) 또는 all(매장 전체). date 조회에서는 무시",
                    schema = @Schema(allowableValues = {"mine", "all"}))
            @RequestParam(required = false) String scope) {
        if ((month == null) == (date == null)) {
            throw validation(List.of(
                    new FieldError("month", "month와 date 중 하나만 보내 주세요."),
                    new FieldError("date", "month와 date 중 하나만 보내 주세요.")));
        }
        if (month != null) {
            ShiftQueryService.MonthView view = shiftQueryService.getMonth(
                    authUser.userId(), storeId, parseMonth(month), parseScope(scope));
            return ApiResponse.ok(ShiftMonthResponse.from(view), clock);
        }
        ShiftQueryService.DayView view = shiftQueryService.getDay(authUser.userId(), storeId, parseDate(date));
        return ApiResponse.ok(ShiftDayResponse.from(view), clock);
    }

    /** SH-01/02: 근무 상세·활성 요청·가능 행동. */
    @Operation(summary = "근무 상세 조회", description = """
            SCH-02 / SH-01, SH-02

            근무 정보·버전·진행 중 대타 요청·내가 할 수 있는 행동(allowed_actions)을 돌려준다.
            allowed_actions는 표시용이고 실제 변경 API가 다시 검사한다. 삭제할 때는 여기의 version을 expected_version으로 보낸다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 근무 매장의 활성 소속이 아님
            - 404 NOT_FOUND: 없는 근무 또는 삭제된 근무
            - 422 VALIDATION_FAILED: shiftId 형식 오류(field: shift_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "근무 상세"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없는·삭제된 근무",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "경로 형식 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/shifts/{shiftId}")
    public ApiResponse<ShiftDetailResponse> detail(@CurrentUser AuthUser authUser,
                                                   @Parameter(description = "근무 id", example = "100")
                                                   @PathVariable long shiftId) {
        return ApiResponse.ok(ShiftDetailResponse.from(shiftQueryService.getDetail(authUser.userId(), shiftId)),
                clock);
    }

    private static YearMonth parseMonth(String month) {
        try {
            if (month.length() == 7) {
                return YearMonth.parse(month);
            }
        } catch (DateTimeParseException ignored) {
            // 아래에서 VALIDATION_FAILED
        }
        throw validation(List.of(new FieldError("month", "yyyy-MM 형식으로 보내 주세요.")));
    }

    private static LocalDate parseDate(String date) {
        try {
            if (date.length() == 10) {
                return LocalDate.parse(date);
            }
        } catch (DateTimeParseException ignored) {
            // 아래에서 VALIDATION_FAILED
        }
        throw validation(List.of(new FieldError("date", "yyyy-MM-dd 형식으로 보내 주세요.")));
    }

    private static Scope parseScope(String scope) {
        if ("mine".equals(scope)) {
            return Scope.MINE;
        }
        if ("all".equals(scope)) {
            return Scope.ALL;
        }
        throw validation(List.of(new FieldError("scope", "mine 또는 all 중 하나로 보내 주세요.")));
    }

    private static BusinessException validation(List<FieldError> errors) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, errors);
    }

    /** 문서 전용: month 조회 응답 봉투(list()의 반환 타입이 ApiResponse<?>라 springdoc이 추론하지 못함). */
    @Schema(name = "ApiResponseShiftMonthResponse")
    record MonthEnvelope(ShiftMonthResponse data, ApiResponse.Meta meta) {
    }

    /** 문서 전용: date 조회 응답 봉투. */
    @Schema(name = "ApiResponseShiftDayResponse")
    record DayEnvelope(ShiftDayResponse data, ApiResponse.Meta meta) {
    }
}
