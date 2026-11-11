package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 처리 결과 알림 수신자 계산(정책 N05·7절, 2026-11-11 행동한 본인 제외). */
class TransitionResultRecipientsTest {

    private static final long REQUESTER = 1L;
    private static final long CANDIDATE = 2L;
    private static final long OTHER_TARGET = 3L;
    private static final long MANAGER_A = 10L;
    private static final long MANAGER_B = 11L;

    @Test
    void 지정_대상의_거절이면_요청자_후보_매니저_모두에게_보낸다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(OTHER_TARGET, REQUESTER,
                CANDIDATE, List.of(REQUESTER, CANDIDATE), List.of(MANAGER_A, MANAGER_B));

        assertThat(recipients).containsExactly(REQUESTER, CANDIDATE, MANAGER_A, MANAGER_B);
    }

    @Test
    void 후보가_수락하면_후보_본인은_받지_않는다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(CANDIDATE, REQUESTER,
                CANDIDATE, List.of(REQUESTER, CANDIDATE), List.of(MANAGER_A));

        assertThat(recipients).containsExactly(REQUESTER, MANAGER_A);
    }

    @Test
    void 요청자가_취소하면_요청자_본인은_받지_않는다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(REQUESTER, REQUESTER,
                CANDIDATE, List.of(REQUESTER, CANDIDATE), List.of(MANAGER_A));

        assertThat(recipients).containsExactly(CANDIDATE, MANAGER_A);
    }

    @Test
    void 매니저가_처리하면_그_매니저만_빠지고_다른_매니저는_받는다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(MANAGER_A, REQUESTER,
                CANDIDATE, List.of(REQUESTER, CANDIDATE), List.of(MANAGER_A, MANAGER_B));

        assertThat(recipients).containsExactly(REQUESTER, CANDIDATE, MANAGER_B);
    }

    @Test
    void 후보가_없으면_후보_수신자도_없다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(OTHER_TARGET, REQUESTER, null,
                List.of(REQUESTER), List.of(MANAGER_A));

        assertThat(recipients).containsExactly(REQUESTER, MANAGER_A);
    }

    @Test
    void 소속이_해제된_요청자_후보는_제외한다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(MANAGER_A, REQUESTER,
                CANDIDATE, List.of(), List.of(MANAGER_A, MANAGER_B));

        assertThat(recipients).containsExactly(MANAGER_B);
    }

    @Test
    void 같은_사람은_한_번만_보낸다() {
        Set<Long> recipients = SubstituteRequestTransitionService.resultRecipients(OTHER_TARGET, REQUESTER,
                CANDIDATE, List.of(REQUESTER, CANDIDATE), List.of(MANAGER_A, MANAGER_A));

        assertThat(recipients).containsExactly(REQUESTER, CANDIDATE, MANAGER_A);
    }
}
