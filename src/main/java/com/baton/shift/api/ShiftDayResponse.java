package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.DayView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** headcount는 중복 제거 인원, shift_count는 근무 건수. */
public record ShiftDayResponse(
        @Schema(description = "조회한 날짜(Asia/Seoul)", example = "2026-11-11")
        LocalDate date,
        @Schema(description = "그날 근무자 수(담당자 중복 제거)", example = "2")
        long headcount,
        @Schema(description = "그날 근무 건수", example = "2")
        long shiftCount,
        @ArraySchema(arraySchema = @Schema(description = "그날 매장 전체 근무. 시작 시각 → 이름 → membership_id 순, 개수 제한 없음"))
        List<ShiftItemResponse> shifts
) {

    static ShiftDayResponse from(DayView view) {
        return new ShiftDayResponse(view.date(), view.headcount(), view.shiftCount(),
                view.shifts().stream().map(ShiftItemResponse::from).toList());
    }
}
