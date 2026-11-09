package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.shift.application.ShiftQueryService;
import com.baton.shift.application.ShiftQueryService.Scope;
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

@RestController
@RequiredArgsConstructor
public class ShiftQueryController {

    private final ShiftQueryService shiftQueryService;
    private final Clock clock;

    /**
     * CA-01/02: {@code ?month=yyyy-MM&scope=mine|all} 월 달력. CA-03: {@code ?date=yyyy-MM-dd} 선택일 전체 명단.
     * month와 date는 정확히 하나만. month 조회는 scope 필수, date 조회는 scope를 무시한다(항상 매장 전체).
     */
    @GetMapping("/stores/{storeId}/shifts")
    public ApiResponse<?> list(@CurrentUser AuthUser authUser, @PathVariable long storeId,
                               @RequestParam(required = false) String month,
                               @RequestParam(required = false) String date,
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
    @GetMapping("/shifts/{shiftId}")
    public ApiResponse<ShiftDetailResponse> detail(@CurrentUser AuthUser authUser, @PathVariable long shiftId) {
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
}
