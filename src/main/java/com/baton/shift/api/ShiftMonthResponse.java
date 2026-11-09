package com.baton.shift.api;

import com.baton.shift.application.ShiftQueryService.MonthView;
import java.time.LocalDate;
import java.util.List;

/** days는 근무가 있는 날만. scope=all이면 shifts는 빈 배열. */
public record ShiftMonthResponse(String month, List<DayCountResponse> days, List<ShiftItemResponse> shifts) {

    static ShiftMonthResponse from(MonthView view) {
        return new ShiftMonthResponse(
                view.month().toString(),
                view.days().stream().map(d -> new DayCountResponse(d.date(), d.headcount())).toList(),
                view.shifts().stream().map(ShiftItemResponse::from).toList());
    }

    public record DayCountResponse(LocalDate date, long headcount) {
    }
}
