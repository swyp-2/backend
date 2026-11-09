package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.DayView;
import java.time.LocalDate;
import java.util.List;

/** headcount는 중복 제거 인원, shift_count는 근무 건수. */
public record ShiftDayResponse(LocalDate date, long headcount, long shiftCount, List<ShiftItemResponse> shifts) {

    static ShiftDayResponse from(DayView view) {
        return new ShiftDayResponse(view.date(), view.headcount(), view.shiftCount(),
                view.shifts().stream().map(ShiftItemResponse::from).toList());
    }
}
