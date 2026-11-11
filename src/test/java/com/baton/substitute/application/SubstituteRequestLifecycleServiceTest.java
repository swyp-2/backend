package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.MembershipQuery;
import com.baton.integration.StoreManagerQuery;
import com.baton.notification.application.NotificationWriter;
import com.baton.notification.domain.NotificationRepository;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.RequestRecipientRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubstituteRequestLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final long SHIFT_ID = 5L;
    private static final long REQUESTER = 10L;

    private final SubstituteRequestRepository repository = mock(SubstituteRequestRepository.class);
    private final SubstituteRequestLifecycleService service = new SubstituteRequestLifecycleService(repository,
            mock(RequestRecipientRepository.class), mock(RequestEventRepository.class), mock(NotificationWriter.class),
            mock(NotificationRepository.class), mock(MembershipQuery.class), mock(StoreManagerQuery.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 진행_중_요청이_없으면_요약도_없다() {
        when(repository.findFirstByShiftIdAndStatusIn(eq(SHIFT_ID), any())).thenReturn(Optional.empty());

        assertThat(service.findActiveByShift(SHIFT_ID, REQUESTER)).isEmpty();
    }

    @Test
    void 요청자가_보면_viewerIsRequester가_true() {
        when(repository.findFirstByShiftIdAndStatusIn(eq(SHIFT_ID), any())).thenReturn(Optional.of(waiting()));

        assertThat(service.findActiveByShift(SHIFT_ID, REQUESTER))
                .contains(new ActiveRequestSummary(7L, "WAITING", true, false));
    }

    @Test
    void 승인_대기면_pendingManagerApproval이_true() {
        SubstituteRequest request = waiting();
        request.accept(20L, NOW);
        when(repository.findFirstByShiftIdAndStatusIn(eq(SHIFT_ID), any())).thenReturn(Optional.of(request));

        assertThat(service.findActiveByShift(SHIFT_ID, 99L))
                .contains(new ActiveRequestSummary(7L, "PENDING_MANAGER", false, true));
    }

    private static SubstituteRequest waiting() {
        Instant start = NOW.plus(Duration.ofDays(1));
        SubstituteRequest request = SubstituteRequest.open(1L, SHIFT_ID, REQUESTER, start,
                start.plus(Duration.ofHours(8)), 0L, NOW);
        ReflectionTestUtils.setField(request, "id", 7L);
        return request;
    }
}
