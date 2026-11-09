package com.baton.shift.application;

import java.util.List;

/** 일괄 등록 계산 결과. startsAt 오름차순, 같으면 membershipId 오름차순으로 정렬돼 있다. */
public record ShiftPlan(List<PlannedShift> shifts) {

    public ShiftPlan {
        shifts = List.copyOf(shifts);
    }

    public int size() {
        return shifts.size();
    }
}
