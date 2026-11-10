package com.baton.shift.application;

import java.time.Instant;
import java.time.LocalDate;

/** 계산된 근무 1건. localDate는 startsAt의 Asia/Seoul 날짜(자정 넘는 근무는 시작일). */
public record PlannedShift(long membershipId, Instant startsAt, Instant endsAt, LocalDate localDate) {
}
