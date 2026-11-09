package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baton.integration.ActiveRequestSummary;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubstituteRequestLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final long SHIFT_ID = 5L;
    private static final long REQUESTER = 10L;

    private final SubstituteRequestRepository repository = mock(SubstituteRequestRepository.class);
    private final SubstituteRequestLifecycleService service = new SubstituteRequestLifecycleService(repository);

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

    @Test
    void 아직_구현_전인_정리와_파기는_예외_없이_끝난다() {
        assertThat(service.closeBySourceDeleted(1L, 2L)).isEmpty();
        assertThatCode(() -> service.cleanupOnMembershipLeave(1L)).doesNotThrowAnyException();
        assertThatCode(() -> service.anonymizeFor(1L)).doesNotThrowAnyException();
    }

    private static SubstituteRequest waiting() {
        Instant start = NOW.plus(Duration.ofDays(1));
        SubstituteRequest request = SubstituteRequest.open(1L, SHIFT_ID, REQUESTER, start,
                start.plus(Duration.ofHours(8)), 0L, NOW);
        ReflectionTestUtils.setField(request, "id", 7L);
        return request;
    }
}
