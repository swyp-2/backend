package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.application.MembershipGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class ShiftCommandServiceTest {

    private static final long USER = 10L;
    private static final long STORE = 1L;
    private static final long MANAGER = 100L;
    private static final long ASSIGNEE = 200L;
    private static final long SHIFT = 5L;
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final Instant START = NOW.minus(Duration.ofDays(1)); // 과거 근무

    MembershipGuard guard;
    ShiftRepository shiftRepository;
    ShiftEventRepository eventRepository;
    ShiftLocker locker;
    ShiftEventValues eventValues;
    SubstituteRequestLifecycle lifecycle;
    ShiftCommandService service;
    Shift shift;

    @BeforeEach
    void setUp() {
        guard = mock(MembershipGuard.class);
        shiftRepository = mock(ShiftRepository.class);
        eventRepository = mock(ShiftEventRepository.class);
        locker = mock(ShiftLocker.class);
        eventValues = mock(ShiftEventValues.class);
        lifecycle = mock(SubstituteRequestLifecycle.class);
        service = new ShiftCommandService(guard, shiftRepository, eventRepository, locker, eventValues, lifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC));

        shift = Shift.schedule(STORE, ASSIGNEE, START, START.plus(Duration.ofHours(4)), NOW.minus(Duration.ofDays(7)));
        when(shiftRepository.findById(SHIFT)).thenReturn(Optional.of(shift));
        when(guard.requireManager(USER, STORE)).thenReturn(new ActiveMembership(MANAGER, STORE, USER, "MANAGER"));
        when(locker.lock(SHIFT)).thenReturn(shift);
        when(eventValues.snapshot(shift)).thenReturn("{\"before\":1}");
        when(lifecycle.closeBySourceDeleted(SHIFT, MANAGER)).thenReturn(Optional.of(77L));
    }

    @Test
    void 잠금_후_삭제하고_DELETED를_기록한_뒤_요청을_종료한다() {
        ShiftCommandService.Deleted result = service.delete(USER, SHIFT, 0L);

        assertThat(result).isEqualTo(new ShiftCommandService.Deleted(SHIFT, 77L));
        assertThat(shift.getDeletedAt()).isEqualTo(NOW);

        InOrder order = inOrder(guard, locker, eventRepository, lifecycle);
        order.verify(guard).requireManager(USER, STORE);
        order.verify(locker).lock(SHIFT);
        ArgumentCaptor<ShiftEvent> captor = ArgumentCaptor.forClass(ShiftEvent.class);
        order.verify(eventRepository).saveAndFlush(captor.capture());
        order.verify(lifecycle).closeBySourceDeleted(SHIFT, MANAGER);

        ShiftEvent event = captor.getValue();
        assertThat(event.getShiftId()).isEqualTo(SHIFT);
        assertThat(event.getEventKind()).isEqualTo(ShiftEventKind.DELETED);
        assertThat(event.getBeforeValue()).isEqualTo("{\"before\":1}");
        assertThat(event.getAfterValue()).isNull();
        assertThat(event.getActorMembershipId()).isEqualTo(MANAGER);
        assertThat(event.getRequestId()).isNull();
        assertThat(event.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void 종료한_요청이_없으면_closed_request_id는_null() {
        when(lifecycle.closeBySourceDeleted(SHIFT, MANAGER)).thenReturn(Optional.empty());

        assertThat(service.delete(USER, SHIFT, 0L).closedRequestId()).isNull();
    }

    @Test
    void 권한이_없으면_잠그지도_않는다() {
        when(guard.requireManager(USER, STORE)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.delete(USER, SHIFT, 0L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(locker, eventRepository, lifecycle);
    }

    @Test
    void 버전이_다르면_CHANGED이고_아무것도_바꾸지_않는다() {
        assertThatThrownBy(() -> service.delete(USER, SHIFT, 3L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIFT_STATE_CONFLICT);
                    assertThat(e.getCurrentState()).isEqualTo("CHANGED");
                });
        assertThat(shift.isDeleted()).isFalse();
        verify(eventRepository, never()).saveAndFlush(any());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void 이미_삭제됐으면_DELETED() {
        shift.delete(NOW.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> service.delete(USER, SHIFT, 0L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIFT_STATE_CONFLICT);
                    assertThat(e.getCurrentState()).isEqualTo("DELETED");
                });
        verifyNoInteractions(lifecycle);
    }

    @Test
    void 없는_근무는_NOT_FOUND() {
        assertThatThrownBy(() -> service.delete(USER, 999L, 0L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(guard, locker);
    }
}
