package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class ShiftWithdrawalServiceTest {

    private final ShiftRepository shifts = mock(ShiftRepository.class);
    private final ShiftLocker locker = mock(ShiftLocker.class);
    private final ShiftEventRepository events = mock(ShiftEventRepository.class);
    private final ShiftEventValues values = mock(ShiftEventValues.class);
    private final SubstituteRequestLifecycle requests = mock(SubstituteRequestLifecycle.class);
    private final ShiftWithdrawalService service = new ShiftWithdrawalService(shifts, locker, events, values, requests);

    @Test
    void 남은_근무를_삭제하고_관련_요청을_종료한다() {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");
        Shift shift = shift(17L, 11L, now.plusSeconds(3600));
        given(shifts.findUnfinishedIdsByAssignee(11L, now)).willReturn(List.of(17L));
        given(locker.lock(17L)).willReturn(shift);
        given(values.snapshot(shift)).willReturn("{\"assignee_membership_id\":11}");

        service.deleteUnfinished(11L, now);

        assertThat(shift.getDeletedAt()).isEqualTo(now);
        ArgumentCaptor<ShiftEvent> event = ArgumentCaptor.forClass(ShiftEvent.class);
        InOrder order = inOrder(shifts, locker, events, requests);
        order.verify(shifts).findUnfinishedIdsByAssignee(11L, now);
        order.verify(locker).lock(17L);
        order.verify(shifts).flush();
        order.verify(events).saveAndFlush(event.capture());
        order.verify(requests).closeBySourceDeleted(17L, 11L);
        assertThat(event.getValue().getEventKind()).isEqualTo(ShiftEventKind.DELETED);
        assertThat(event.getValue().getActorMembershipId()).isEqualTo(11L);
    }

    @Test
    void 잠금_후_담당자가_바뀐_근무는_건드리지_않는다() {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");
        Shift shift = shift(17L, 12L, now.plusSeconds(3600));
        given(shifts.findUnfinishedIdsByAssignee(11L, now)).willReturn(List.of(17L));
        given(locker.lock(17L)).willReturn(shift);

        service.deleteUnfinished(11L, now);

        assertThat(shift.isDeleted()).isFalse();
        verifyNoInteractions(events, requests);
    }

    private static Shift shift(long id, long membershipId, Instant endsAt) {
        Shift shift = Shift.schedule(5L, membershipId, endsAt.minusSeconds(3600), endsAt,
                endsAt.minusSeconds(7200));
        ReflectionTestUtils.setField(shift, "id", id);
        return shift;
    }
}
