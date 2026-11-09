package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.substitute.domain.RequestRecipient;
import com.baton.substitute.domain.SubstituteRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AllowedActionsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(1));
    private static final long REQUESTER = 10L;
    private static final long CANDIDATE = 20L;

    private static SubstituteRequest waiting() {
        return SubstituteRequest.open(1L, 2L, REQUESTER, START, START.plus(Duration.ofHours(8)), 0L, NOW);
    }

    private static SubstituteRequest pending() {
        SubstituteRequest request = waiting();
        request.accept(CANDIDATE, NOW);
        return request;
    }

    @Test
    void WAITING의_미응답_대상은_수락과_거절() {
        assertThat(AllowedActions.of(waiting(), RequestRelation.TARGET, RequestRecipient.designate(1L, 30L), NOW))
                .containsExactly("accept", "reject");
    }

    @Test
    void WAITING에서_거절했던_대상은_재수락만() {
        RequestRecipient declined = RequestRecipient.designate(1L, 30L);
        declined.decline(NOW);

        assertThat(AllowedActions.of(waiting(), RequestRelation.TARGET, declined, NOW)).containsExactly("accept");
    }

    @Test
    void 승인_대기에서_후보가_아닌_대상은_행동_없음() {
        assertThat(AllowedActions.of(pending(), RequestRelation.TARGET, RequestRecipient.designate(1L, 30L), NOW))
                .isEmpty();
    }

    @Test
    void 요청자는_진행_중이면_취소() {
        assertThat(AllowedActions.of(waiting(), RequestRelation.REQUESTER, null, NOW)).containsExactly("cancel");
        assertThat(AllowedActions.of(pending(), RequestRelation.REQUESTER, null, NOW)).containsExactly("cancel");
    }

    @Test
    void 후보는_철회() {
        assertThat(AllowedActions.of(pending(), RequestRelation.CANDIDATE, null, NOW)).containsExactly("withdraw");
    }

    @Test
    void 매니저는_승인_대기에서만_승인과_최종_거절() {
        assertThat(AllowedActions.of(waiting(), RequestRelation.MANAGER, null, NOW)).isEmpty();
        assertThat(AllowedActions.of(pending(), RequestRelation.MANAGER, null, NOW))
                .containsExactly("approve", "final_reject");
    }

    @Test
    void 종료됐거나_시작_시각이_지나면_행동_없음() {
        SubstituteRequest cancelled = waiting();
        cancelled.cancel(NOW);

        assertThat(AllowedActions.of(cancelled, RequestRelation.REQUESTER, null, NOW)).isEmpty();
        assertThat(AllowedActions.of(pending(), RequestRelation.MANAGER, null, START)).isEmpty();
    }
}
