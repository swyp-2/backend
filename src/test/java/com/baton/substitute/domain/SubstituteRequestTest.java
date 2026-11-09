package com.baton.substitute.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SubstituteRequestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = CLOCK.instant();
    private static final Instant START = NOW.plus(Duration.ofDays(1));
    private static final Instant END = START.plus(Duration.ofHours(8));
    private static final long REQUESTER = 10L;
    private static final long CANDIDATE = 20L;
    private static final long OTHER = 30L;

    private static SubstituteRequest waiting() {
        return SubstituteRequest.open(1L, 2L, REQUESTER, START, END, 3L, NOW);
    }

    private static SubstituteRequest pending() {
        SubstituteRequest request = waiting();
        request.accept(CANDIDATE, NOW);
        return request;
    }

    @Test
    void 새_요청은_WAITING이고_스냅샷을_보관한다() {
        SubstituteRequest request = waiting();

        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.WAITING);
        assertThat(request.isActive()).isTrue();
        assertThat(request.getCandidateMembershipId()).isNull();
        assertThat(request.getSourceStartsAt()).isEqualTo(START);
        assertThat(request.getSourceEndsAt()).isEqualTo(END);
        assertThat(request.getSourceVersion()).isEqualTo(3L);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getClosedAt()).isNull();
    }

    @Nested
    class 수락 {

        @Test
        void 첫_수락은_후보를_확정하고_승인_대기로_바꾼다() {
            SubstituteRequest request = pending();

            assertThat(request.getStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
            assertThat(request.getCandidateMembershipId()).isEqualTo(CANDIDATE);
            assertThat(request.isCandidate(CANDIDATE)).isTrue();
            assertThat(request.getClosedAt()).isNull();
        }

        @Test
        void 이미_후보가_있으면_두_번째_수락은_충돌() {
            SubstituteRequest request = pending();

            assertConflict(() -> request.accept(OTHER, NOW), SubstituteStatus.PENDING_MANAGER);
            assertThat(request.getCandidateMembershipId()).isEqualTo(CANDIDATE);
        }

        @Test
        void 시작_시각_정각부터는_수락할_수_없다() {
            SubstituteRequest request = waiting();

            assertError(() -> request.accept(CANDIDATE, START), ErrorCode.REQUEST_EXPIRED);
            assertThat(request.getStatus()).isEqualTo(SubstituteStatus.WAITING);
        }

        @Test
        void 요청자는_후보가_될_수_없다() {
            assertError(() -> waiting().accept(REQUESTER, NOW), ErrorCode.FORBIDDEN);
        }

        @Test
        void 전원_거절로_종료된_요청은_다시_수락할_수_없다() {
            SubstituteRequest request = waiting();
            request.closeAllDeclined(NOW);

            assertConflict(() -> request.accept(CANDIDATE, NOW), SubstituteStatus.ALL_DECLINED);
        }
    }

    @Nested
    class 사용자_행동_종료 {

        @Test
        void 전원_거절은_WAITING에서만() {
            SubstituteRequest request = waiting();
            request.closeAllDeclined(NOW);

            assertClosed(request, SubstituteStatus.ALL_DECLINED, null);
            assertConflict(() -> pending().closeAllDeclined(NOW), SubstituteStatus.PENDING_MANAGER);
        }

        @Test
        void 요청자_취소는_WAITING과_PENDING_MANAGER에서_가능() {
            SubstituteRequest fromWaiting = waiting();
            fromWaiting.cancel(NOW);
            SubstituteRequest fromPending = pending();
            fromPending.cancel(NOW);

            assertClosed(fromWaiting, SubstituteStatus.CANCELLED, null);
            assertClosed(fromPending, SubstituteStatus.CANCELLED, null);
            assertThat(fromPending.getCandidateMembershipId()).isEqualTo(CANDIDATE);
        }

        @Test
        void 후보의_철회는_요청_전체를_WITHDRAWN으로_종료한다() {
            SubstituteRequest request = pending();
            request.withdraw(CANDIDATE, NOW);

            assertClosed(request, SubstituteStatus.WITHDRAWN, null);
        }

        @Test
        void 후보가_아니면_철회할_수_없다() {
            SubstituteRequest request = pending();

            assertError(() -> request.withdraw(OTHER, NOW), ErrorCode.FORBIDDEN);
            assertThat(request.getStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
        }

        @Test
        void WAITING에서는_철회할_수_없다() {
            assertConflict(() -> waiting().withdraw(CANDIDATE, NOW), SubstituteStatus.WAITING);
        }

        @Test
        void 매니저_승인은_PENDING_MANAGER에서만() {
            SubstituteRequest request = pending();
            request.approve(NOW);

            assertClosed(request, SubstituteStatus.APPROVED, null);
            assertConflict(() -> waiting().approve(NOW), SubstituteStatus.WAITING);
        }

        @Test
        void 시작_시각_이후에는_승인할_수_없다() {
            SubstituteRequest request = pending();

            assertError(() -> request.approve(START), ErrorCode.REQUEST_EXPIRED);
            assertThat(request.getStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
        }

        @Test
        void 매니저_최종_거절은_PENDING_MANAGER에서만() {
            SubstituteRequest request = pending();
            request.reject(NOW);

            assertClosed(request, SubstituteStatus.REJECTED, null);
            assertConflict(() -> waiting().reject(NOW), SubstituteStatus.WAITING);
        }
    }

    @Nested
    class 시스템_종료 {

        @Test
        void 시작_시각_정각에_진행_중_요청은_만료된다() {
            SubstituteRequest fromWaiting = waiting();
            fromWaiting.expire(START);
            SubstituteRequest fromPending = pending();
            fromPending.expire(START);

            assertClosed(fromWaiting, SubstituteStatus.EXPIRED, null);
            assertClosed(fromPending, SubstituteStatus.EXPIRED, null);
        }

        @Test
        void 시작_전에는_만료시킬_수_없다() {
            SubstituteRequest request = waiting();

            assertConflict(() -> request.expire(START.minusSeconds(1)), SubstituteStatus.WAITING);
        }

        @Test
        void 근무_삭제는_CANCELLED_SOURCE_CHANGED와_SHIFT_DELETED() {
            SubstituteRequest request = pending();
            request.closeBySourceDeleted(NOW);

            assertClosed(request, SubstituteStatus.CANCELLED_SOURCE_CHANGED, TerminalReason.SHIFT_DELETED);
        }

        @Test
        void 요청자_소속_해제는_CANCELLED와_REQUESTER_LEFT() {
            SubstituteRequest request = pending();
            request.cancelByRequesterLeft(NOW);

            assertClosed(request, SubstituteStatus.CANCELLED, TerminalReason.REQUESTER_LEFT);
        }

        @Test
        void 후보_소속_해제는_WITHDRAWN과_CANDIDATE_LEFT() {
            SubstituteRequest request = pending();
            request.withdrawByCandidateLeft(NOW);

            assertClosed(request, SubstituteStatus.WITHDRAWN, TerminalReason.CANDIDATE_LEFT);
            assertConflict(() -> waiting().withdrawByCandidateLeft(NOW), SubstituteStatus.WAITING);
        }

        @Test
        void 응답_가능_대상_0명은_CANCELLED와_NO_ACTIVE_TARGET() {
            SubstituteRequest request = waiting();
            request.cancelForNoActiveTarget(NOW);

            assertClosed(request, SubstituteStatus.CANCELLED, TerminalReason.NO_ACTIVE_TARGET);
            assertConflict(() -> pending().cancelForNoActiveTarget(NOW), SubstituteStatus.PENDING_MANAGER);
        }
    }

    @Test
    void 종료된_요청은_어떤_행동으로도_덮어쓰지_않는다() {
        SubstituteRequest request = pending();
        request.approve(NOW);
        Instant closedAt = request.getClosedAt();

        List<Consumer<SubstituteRequest>> actions = List.of(
                r -> r.accept(OTHER, NOW),
                r -> r.closeAllDeclined(NOW),
                r -> r.cancel(NOW),
                r -> r.withdraw(CANDIDATE, NOW),
                r -> r.approve(NOW),
                r -> r.reject(NOW),
                r -> r.expire(START),
                r -> r.closeBySourceDeleted(NOW),
                r -> r.cancelByRequesterLeft(NOW),
                r -> r.withdrawByCandidateLeft(NOW),
                r -> r.cancelForNoActiveTarget(NOW));

        for (Consumer<SubstituteRequest> action : actions) {
            assertConflict(() -> action.accept(request), SubstituteStatus.APPROVED);
        }
        assertClosed(request, SubstituteStatus.APPROVED, null);
        assertThat(request.getClosedAt()).isEqualTo(closedAt);
    }

    private static void assertClosed(SubstituteRequest request, SubstituteStatus status, TerminalReason reason) {
        assertThat(request.getStatus()).isEqualTo(status);
        assertThat(request.getTerminalReason()).isEqualTo(reason);
        assertThat(request.isActive()).isFalse();
        assertThat(request.getClosedAt()).isNotNull();
    }

    private static void assertConflict(ThrowingCallable call, SubstituteStatus current) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REQUEST_STATE_CONFLICT);
                    assertThat(e.getCurrentState()).isEqualTo(current.name());
                });
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
