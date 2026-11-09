package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.AccountState;
import com.baton.shift.domain.ShiftRepository;
import com.baton.shift.domain.ShiftRow;
import com.baton.store.application.MembershipGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShiftQueryServiceTest {

    private static final long USER = 10L;
    private static final long STORE = 1L;
    private static final long ME = 100L;
    private static final long OTHER = 200L;
    private static final long SHIFT = 5L;
    private static final Instant START = Instant.parse("2026-11-30T13:00:00Z"); // KST 11/30 22:00

    MembershipGuard guard;
    ShiftRepository shiftRepository;
    SubstituteRequestLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        guard = mock(MembershipGuard.class);
        shiftRepository = mock(ShiftRepository.class);
        lifecycle = mock(SubstituteRequestLifecycle.class);
        when(lifecycle.findActiveByShift(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void 크루원_내_근무_시작_1분_전이면_대타_요청_가능() {
        givenViewer("CREW");
        givenShift(ME);

        ShiftQueryService.ShiftDetailView view = service(START.minus(Duration.ofMinutes(1))).getDetail(USER, SHIFT);

        assertThat(view.isMine()).isTrue();
        assertThat(view.isStarted()).isFalse();
        assertThat(view.allowedActions()).containsExactly("request_substitute");
        assertThat(view.activeRequest()).isNull();
    }

    @Test
    void 크루원_내_근무_시작_1분_후면_행동_없음() {
        givenViewer("CREW");
        givenShift(ME);

        ShiftQueryService.ShiftDetailView view = service(START.plus(Duration.ofMinutes(1))).getDetail(USER, SHIFT);

        assertThat(view.isStarted()).isTrue();
        assertThat(view.allowedActions()).isEmpty();
    }

    @Test
    void 시작_시각_정각이면_시작한_것으로_본다() {
        givenViewer("CREW");
        givenShift(ME);

        ShiftQueryService.ShiftDetailView view = service(START).getDetail(USER, SHIFT);

        assertThat(view.isStarted()).isTrue();
        assertThat(view.allowedActions()).isEmpty();
    }

    @Test
    void 크루원_내_근무에_활성_요청이_있으면_요청_보기() {
        givenViewer("CREW");
        givenShift(ME);
        ActiveRequestSummary request = new ActiveRequestSummary(77L, "OPEN", true, false);
        when(lifecycle.findActiveByShift(SHIFT, ME)).thenReturn(Optional.of(request));

        ShiftQueryService.ShiftDetailView view = service(START.minus(Duration.ofMinutes(1))).getDetail(USER, SHIFT);

        assertThat(view.activeRequest()).isEqualTo(request);
        assertThat(view.allowedActions()).containsExactly("view_request");
    }

    @Test
    void 크루원이_타인_근무를_보면_행동_없음() {
        givenViewer("CREW");
        givenShift(OTHER);

        ShiftQueryService.ShiftDetailView view = service(START.minus(Duration.ofMinutes(1))).getDetail(USER, SHIFT);

        assertThat(view.isMine()).isFalse();
        assertThat(view.allowedActions()).isEmpty();
    }

    @Test
    void 매니저는_시작_후에도_삭제_가능하고_승인_대기면_승인_요청_보기() {
        givenViewer("MANAGER");
        givenShift(OTHER);
        when(lifecycle.findActiveByShift(SHIFT, ME))
                .thenReturn(Optional.of(new ActiveRequestSummary(77L, "PENDING_MANAGER", false, true)));

        ShiftQueryService.ShiftDetailView view = service(START.plus(Duration.ofMinutes(1))).getDetail(USER, SHIFT);

        assertThat(view.allowedActions()).containsExactly("delete", "view_approval_request");
    }

    @Test
    void 삭제됐거나_없는_근무는_NOT_FOUND() {
        when(shiftRepository.findLiveRowById(SHIFT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(START).getDetail(USER, SHIFT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 자정을_넘는_근무의_날짜는_시작일() {
        assertThat(ShiftQueryService.localDateOf(START)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(ShiftQueryService.localDateOf(Instant.parse("2026-11-30T15:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 12, 1)); // KST 12/01 00:00
    }

    @Test
    void 탈퇴한_담당자는_탈퇴한_구성원과_회색으로_표시() {
        givenViewer("CREW");
        when(shiftRepository.findLiveRowById(SHIFT)).thenReturn(Optional.of(new ShiftRow(SHIFT, STORE, OTHER, START,
                START.plus(Duration.ofHours(8)), 0L, null, null, null, "#E5484D")));

        ShiftQueryService.ShiftDetailView view = service(START).getDetail(USER, SHIFT);

        assertThat(view.item().assignee().displayName()).isEqualTo("탈퇴한 구성원");
        assertThat(view.item().assignee().personalColor()).isEqualTo("#B0B4BA");
        assertThat(view.item().assignee().isMe()).isFalse();
    }

    private ShiftQueryService service(Instant now) {
        return new ShiftQueryService(guard, shiftRepository, lifecycle, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void givenViewer(String role) {
        when(guard.requireActive(USER, STORE)).thenReturn(new ActiveMembership(ME, STORE, USER, role));
    }

    private void givenShift(long assignee) {
        when(shiftRepository.findLiveRowById(SHIFT)).thenReturn(Optional.of(new ShiftRow(SHIFT, STORE, assignee,
                START, START.plus(Duration.ofHours(8)), 3L, 999L, "이름", AccountState.ACTIVE, "#E5484D")));
    }
}
