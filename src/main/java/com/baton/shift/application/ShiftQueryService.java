package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.AccountState;
import com.baton.member.domain.Role;
import com.baton.shift.domain.ShiftRepository;
import com.baton.shift.domain.ShiftRow;
import com.baton.store.application.MembershipGuard;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCH-01/02 근무 조회. 근무는 starts_at을 Asia/Seoul로 바꾼 날짜에만 속한다(자정 넘는 근무는 시작일, ERD 7절).
 * 인원수는 담당 소속 중복 제거, 근무 건수는 근무별(B22·D01).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftQueryService {

    public static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");
    /** 탈퇴 담당자 표시 색. 12색 팔레트 밖의 회색이라 살아 있는 구성원과 겹치지 않는다. */
    public static final String WITHDRAWN_COLOR = "#B0B4BA";

    private final MembershipGuard membershipGuard;
    private final ShiftRepository shiftRepository;
    private final SubstituteRequestLifecycle substituteRequestLifecycle;
    private final Clock clock;

    /** CA-01/02: 월 달력. mine은 내 근무 기준 인원 + 월 전체 내 근무 목록, all은 매장 기준 인원만(목록은 날짜 조회로). */
    public MonthView getMonth(long userId, long storeId, YearMonth month, Scope scope) {
        ActiveMembership me = membershipGuard.requireActive(userId, storeId);
        Instant from = month.atDay(1).atStartOfDay(STORE_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(STORE_ZONE).toInstant();

        if (scope == Scope.ALL) {
            List<ShiftRow> rows = shiftRepository.findLiveRowsInStore(storeId, from, to);
            return new MonthView(month, dayCounts(rows), List.of());
        }
        List<ShiftRow> rows = shiftRepository.findLiveRowsOfAssignee(storeId, me.membershipId(), from, to);
        List<ShiftItemView> shifts = rows.stream()
                .sorted(Comparator.comparing(ShiftRow::startsAt).thenComparing(ShiftRow::shiftId))
                .map(row -> toItem(row, me.membershipId()))
                .toList();
        return new MonthView(month, dayCounts(rows), shifts);
    }

    /** CA-03: 선택일 전체 명단. 시작 시각 → 한국어 이름 → membership_id 순, 개수 제한 없음. */
    public DayView getDay(long userId, long storeId, LocalDate date) {
        ActiveMembership me = membershipGuard.requireActive(userId, storeId);
        Instant from = date.atStartOfDay(STORE_ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(STORE_ZONE).toInstant();
        List<ShiftRow> rows = shiftRepository.findLiveRowsInStore(storeId, from, to);

        Collator collator = Collator.getInstance(Locale.KOREAN);
        List<ShiftItemView> shifts = rows.stream()
                .map(row -> toItem(row, me.membershipId()))
                .sorted(Comparator.comparing(ShiftItemView::startsAt)
                        .thenComparing(item -> item.assignee().displayName(), collator)
                        .thenComparing(item -> item.assignee().membershipId())
                        .thenComparing(ShiftItemView::shiftId))
                .toList();
        return new DayView(date, distinctAssignees(rows), shifts.size(), shifts);
    }

    /** SH-01/02: 근무 상세. 미존재·삭제는 404, 근무 매장에 활성 소속이 아니면 403. */
    public ShiftDetailView getDetail(long userId, long shiftId) {
        ShiftRow row = shiftRepository.findLiveRowById(shiftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ActiveMembership me = membershipGuard.requireActive(userId, row.storeId());
        ActiveRequestSummary activeRequest = substituteRequestLifecycle
                .findActiveByShift(shiftId, me.membershipId())
                .orElse(null);

        boolean isMine = row.assigneeMembershipId() == me.membershipId();
        boolean isStarted = !clock.instant().isBefore(row.startsAt());
        List<String> actions = ShiftActions.of(Role.valueOf(me.role()), isMine, isStarted, activeRequest);
        return new ShiftDetailView(toItem(row, me.membershipId()), row.version(), isMine, isStarted, activeRequest,
                actions);
    }

    static LocalDate localDateOf(Instant startsAt) {
        return startsAt.atZone(STORE_ZONE).toLocalDate();
    }

    private static List<DayCountView> dayCounts(List<ShiftRow> rows) {
        Map<LocalDate, Long> counts = rows.stream().collect(Collectors.groupingBy(
                row -> localDateOf(row.startsAt()), TreeMap::new,
                Collectors.collectingAndThen(
                        Collectors.mapping(ShiftRow::assigneeMembershipId, Collectors.toSet()),
                        set -> (long) set.size())));
        return counts.entrySet().stream()
                .map(entry -> new DayCountView(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static long distinctAssignees(List<ShiftRow> rows) {
        return rows.stream().map(ShiftRow::assigneeMembershipId).distinct().count();
    }

    private static ShiftItemView toItem(ShiftRow row, long viewerMembershipId) {
        boolean withdrawn = row.assigneeUserId() == null || row.accountState() == AccountState.WITHDRAWN;
        AssigneeView assignee = withdrawn
                ? new AssigneeView(row.assigneeMembershipId(), MembershipGuard.WITHDRAWN_MEMBER_NAME, WITHDRAWN_COLOR,
                        false)
                : new AssigneeView(row.assigneeMembershipId(), row.displayName(), row.personalColor(),
                        row.assigneeMembershipId() == viewerMembershipId);
        return new ShiftItemView(row.shiftId(), row.startsAt(), row.endsAt(), localDateOf(row.startsAt()), assignee);
    }

    public enum Scope {
        MINE,
        ALL
    }

    public record AssigneeView(long membershipId, String displayName, String personalColor, boolean isMe) {
    }

    public record ShiftItemView(long shiftId, Instant startsAt, Instant endsAt, LocalDate localDate,
                                AssigneeView assignee) {
    }

    public record DayCountView(LocalDate date, long headcount) {
    }

    public record MonthView(YearMonth month, List<DayCountView> days, List<ShiftItemView> shifts) {
    }

    public record DayView(LocalDate date, long headcount, long shiftCount, List<ShiftItemView> shifts) {
    }

    /** @param activeRequest 없으면 null */
    public record ShiftDetailView(ShiftItemView item, long version, boolean isMine, boolean isStarted,
                                  ActiveRequestSummary activeRequest, List<String> allowedActions) {
    }
}
