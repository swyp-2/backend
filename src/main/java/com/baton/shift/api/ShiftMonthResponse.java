package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.MonthView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** days는 근무가 있는 날만. scope=all이면 shifts는 빈 배열. */
public record ShiftMonthResponse(
        @Schema(description = "조회한 월(yyyy-MM, Asia/Seoul)", example = "2026-11")
        String month,
        @ArraySchema(arraySchema = @Schema(description = "근무가 있는 날만, 날짜 오름차순. scope=mine이면 내 근무 기준, all이면 매장 전체 기준"))
        List<DayCountResponse> days,
        @ArraySchema(arraySchema = @Schema(description = "scope=mine이면 그 달 내 근무 전체(시작 시각 → shift_id 순), scope=all이면 빈 배열"))
        List<ShiftItemResponse> shifts
) {

    static ShiftMonthResponse from(MonthView view) {
        return new ShiftMonthResponse(
                view.month().toString(),
                view.days().stream().map(d -> new DayCountResponse(d.date(), d.headcount())).toList(),
                view.shifts().stream().map(ShiftItemResponse::from).toList());
    }

    public record DayCountResponse(
            @Schema(description = "날짜(Asia/Seoul)", example = "2026-11-11")
            LocalDate date,
            @Schema(description = "그날 근무자 수(담당자 중복 제거)", example = "2")
            long headcount
    ) {
    }
}
