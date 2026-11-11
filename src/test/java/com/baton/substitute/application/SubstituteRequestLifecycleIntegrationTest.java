package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.integration.MembershipQuery;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import com.baton.substitute.domain.TerminalReason;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SubstituteRequestLifecycle 실제 동작: 근무 삭제(A의 DELETE /shifts 경유), 소속 해제 정리, 탈퇴 파기.
 * A의 나가기·탈퇴 API가 아직 없어, 소속 해제는 A가 할 순서(떠나는 소속 FOR UPDATE → 정리)를 트랜잭션으로 흉내 낸다.
 */
@IntegrationTest
class SubstituteRequestLifecycleIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T05:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired RequestEventRepository eventRepository;
    @Autowired SubstituteRequestLifecycle lifecycle;
    @Autowired MembershipQuery membershipQuery;
    @Autowired MembershipRepository membershipRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    private SubstituteApi api;
    private StoreFixture store;
    private long requester;
    private long target1;
    private long target2;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(3);
        requester = store.crew(0).membershipId();
        target1 = store.crew(1).membershipId();
        target2 = store.crew(2).membershipId();
    }

    // ---- 근무 삭제 ---------------------------------------------------------

    @Test
    void 근무를_삭제하면_진행_중_요청이_삭제_사유로_종료되고_알림은_없다() throws Exception {
        long shiftId = api.shift(store.storeId(), requester, 1);
        long requestId = createRequest(shiftId, List.of(target1));
        int notificationsBefore = notificationCount(requestId);

        deleteShift(shiftId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed_request_id").value(requestId));

        SubstituteRequest request = request(requestId);
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.CANCELLED_SOURCE_CHANGED);
        assertThat(request.getTerminalReason()).isEqualTo(TerminalReason.SHIFT_DELETED);
        assertThat(request.getClosedAt()).isNotNull();
        RequestEvent event = lastEvent(requestId);
        assertThat(event.getKind()).isEqualTo(RequestEventKind.SOURCE_DELETED);
        assertThat(event.getActorMembershipId()).isEqualTo(store.manager().membershipId());
        assertThat(event.getBeforeStatus()).isEqualTo(SubstituteStatus.WAITING);
        assertThat(notificationCount(requestId)).isEqualTo(notificationsBefore);
    }

    @Test
    void 승인_대기_요청도_근무_삭제로_종료된다() throws Exception {
        long shiftId = api.shift(store.storeId(), requester, 1);
        long requestId = createRequest(shiftId, List.of(target1));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        deleteShift(shiftId).andExpect(jsonPath("$.data.closed_request_id").value(requestId));

        assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.CANCELLED_SOURCE_CHANGED);
        assertThat(lastEvent(requestId).getBeforeStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
    }

    @Test
    void 진행_중_요청이_없는_근무를_삭제하면_closed_request_id는_null이고_종료된_요청은_그대로다() throws Exception {
        long shiftId = api.shift(store.storeId(), requester, 1);
        long requestId = createRequest(shiftId, List.of(target1));
        api.transitionOk(store.crew(0).userId(), requestId, "cancel");

        deleteShift(shiftId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed_request_id").isEmpty());

        assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.CANCELLED);
        assertThat(lastEvent(requestId).getKind()).isEqualTo(RequestEventKind.REQUEST_CANCELLED);
    }

    // ---- 소속 해제 ---------------------------------------------------------

    @Test
    void 요청자가_나가면_REQUESTER_LEFT로_취소되고_남은_관계자에게만_알린다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        leave(requester);

        SubstituteRequest request = request(requestId);
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.CANCELLED);
        assertThat(request.getTerminalReason()).isEqualTo(TerminalReason.REQUESTER_LEFT);
        RequestEvent event = lastEvent(requestId);
        assertThat(event.getKind()).isEqualTo(RequestEventKind.MEMBER_LEFT);
        assertThat(event.getActorMembershipId()).isEqualTo(requester);
        assertThat(event.getBeforeStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
        // 직전 후보·매니저만. 떠난 요청자는 받지 않는다
        assertThat(memberLeftRecipients(requestId))
                .containsExactlyInAnyOrder(target1, store.manager().membershipId());
    }

    @Test
    void 승인_대기_후보가_나가면_CANDIDATE_LEFT로_철회된다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1, target2));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        leave(target1);

        SubstituteRequest request = request(requestId);
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.WITHDRAWN);
        assertThat(request.getTerminalReason()).isEqualTo(TerminalReason.CANDIDATE_LEFT);
        assertThat(response(requestId, target1)).isEqualTo("UNAVAILABLE");
        assertThat(memberLeftRecipients(requestId))
                .containsExactlyInAnyOrder(requester, store.manager().membershipId());
    }

    @Test
    void 지정_대상이_나가도_다른_미응답_대상이_있으면_요청은_계속되고_기록과_알림은_없다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1, target2));
        int eventsBefore = eventCount(requestId);

        leave(target1);

        assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.WAITING);
        assertThat(response(requestId, target1)).isEqualTo("UNAVAILABLE");
        assertThat(response(requestId, target2)).isEqualTo("PENDING");
        assertThat(eventCount(requestId)).isEqualTo(eventsBefore);
        assertThat(memberLeftRecipients(requestId)).isEmpty();
    }

    @Test
    void 마지막_미응답_대상이_나가고_거절한_대상만_남으면_ALL_DECLINED() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1, target2));
        api.transitionOk(store.crew(1).userId(), requestId, "reject");

        leave(target2);

        SubstituteRequest request = request(requestId);
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.ALL_DECLINED);
        assertThat(request.getTerminalReason()).isNull();
        assertThat(lastEvent(requestId).getKind()).isEqualTo(RequestEventKind.MEMBER_LEFT);
        assertThat(memberLeftRecipients(requestId))
                .containsExactlyInAnyOrder(requester, store.manager().membershipId());
    }

    @Test
    void 지정_대상이_모두_나가면_NO_ACTIVE_TARGET으로_취소된다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1));

        leave(target1);

        SubstituteRequest request = request(requestId);
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.CANCELLED);
        assertThat(request.getTerminalReason()).isEqualTo(TerminalReason.NO_ACTIVE_TARGET);
        assertThat(memberLeftRecipients(requestId))
                .containsExactlyInAnyOrder(requester, store.manager().membershipId());
    }

    @Test
    void 승인_대기에서_후보가_아닌_지정_대상이_나가면_응답_불가만_표시한다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1, target2));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        leave(target2);

        assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
        assertThat(response(requestId, target2)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void 종료된_요청은_다시_열거나_바꾸지_않는다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1));
        api.transitionOk(store.crew(0).userId(), requestId, "cancel");
        int eventsBefore = eventCount(requestId);

        leave(requester);
        leave(target1);

        assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.CANCELLED);
        assertThat(request(requestId).getTerminalReason()).isNull();
        assertThat(eventCount(requestId)).isEqualTo(eventsBefore);
    }

    @Test
    void 요청자의_여러_진행_중_요청을_모두_정리한다() throws Exception {
        long first = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1));
        long second = createRequest(api.shift(store.storeId(), requester, 2), List.of(target2));

        leave(requester);

        assertThat(request(first).getTerminalReason()).isEqualTo(TerminalReason.REQUESTER_LEFT);
        assertThat(request(second).getTerminalReason()).isEqualTo(TerminalReason.REQUESTER_LEFT);
    }

    @Test
    void 승인과_후보의_소속_해제가_동시에_일어나도_한_번만_처리된다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> approve = executor.submit(() -> {
                start.await();
                return api.transition(store.manager().userId(), requestId, "approve")
                        .andReturn().getResponse().getStatus();
            });
            Future<?> leave = executor.submit(() -> {
                start.await();
                leave(target1);
                return null;
            });
            start.countDown();
            int approveStatus = approve.get();
            leave.get();

            SubstituteRequest request = request(requestId);
            List<RequestEventKind> closing = eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId)
                    .stream()
                    .map(RequestEvent::getKind)
                    .filter(k -> k == RequestEventKind.MANAGER_APPROVED || k == RequestEventKind.MEMBER_LEFT)
                    .toList();
            assertThat(closing).hasSize(1);
            if (approveStatus == 200) {
                assertThat(request.getStatus()).isEqualTo(SubstituteStatus.APPROVED);
                assertThat(closing).containsExactly(RequestEventKind.MANAGER_APPROVED);
            } else {
                assertThat(approveStatus).isEqualTo(409);
                assertThat(request.getStatus()).isEqualTo(SubstituteStatus.WITHDRAWN);
                assertThat(closing).containsExactly(RequestEventKind.MEMBER_LEFT);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 매니저가 나가는 트랜잭션이 소속을 잡고 있는 동안 그 매니저의 승인은 기다리고,
     * 나가기가 확정되면 403으로 끝난다(나간 매니저의 승인이 확정되지 않는다).
     */
    @Test
    void 매니저가_나가는_중이면_그_매니저의_승인은_기다렸다가_403이고_요청은_그대로다() throws Exception {
        long shiftId = api.shift(store.storeId(), requester, 1);
        long requestId = createRequest(shiftId, List.of(target1));
        api.transitionOk(store.crew(1).userId(), requestId, "accept");
        long manager = store.manager().membershipId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            Future<?> leave = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Membership membership = membershipRepository.findActiveByIdForUpdate(manager).orElseThrow();
                membership.leave(clock.instant());
                locked.countDown();
                await(commit);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> approve = executor.submit(() -> api.transition(store.manager().userId(), requestId,
                    "approve").andReturn().getResponse().getStatus());
            waitUntilBlockedOnLock();
            assertThat(approve.isDone()).isFalse();

            commit.countDown();
            leave.get(30, TimeUnit.SECONDS);

            assertThat(approve.get(30, TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(request(requestId).getStatus()).isEqualTo(SubstituteStatus.PENDING_MANAGER);
            assertThat(shiftRepository.findById(shiftId).orElseThrow().getAssigneeMembershipId())
                    .isEqualTo(requester);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    // ---- 탈퇴 파기 ---------------------------------------------------------

    @Test
    void 탈퇴_파기는_본인이_받은_알림만_지운다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), requester, 1), List.of(target1, target2));
        assertThat(notificationCountFor(target1)).isPositive();
        int target2Before = notificationCountFor(target2);

        lifecycle.anonymizeFor(target1);

        assertThat(notificationCountFor(target1)).isZero();
        assertThat(notificationCountFor(target2)).isEqualTo(target2Before);
        assertThat(requestRepository.findById(requestId)).isPresent();
    }

    // ---- helpers ----------------------------------------------------------

    /** 다른 세션이 행 잠금을 기다리기 시작할 때까지(최대 10초) 확인한다. */
    private void waitUntilBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("승인이 매니저 소속 잠금을 기다리지 않았다");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long createRequest(long shiftId, List<Long> targets) throws Exception {
        return api.createRequest(store.crew(0).userId(), shiftId, targets).get("id").asLong();
    }

    /** A의 나가기·탈퇴 순서를 흉내 낸다: 떠나는 소속 FOR UPDATE → 요청 정리를 한 트랜잭션에서. */
    private void leave(long membershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            membershipQuery.lockActiveById(membershipId);
            lifecycle.cleanupOnMembershipLeave(membershipId);
        });
    }

    private org.springframework.test.web.servlet.ResultActions deleteShift(long shiftId) throws Exception {
        long version = shiftRepository.findById(shiftId).orElseThrow().getVersion();
        return mockMvc.perform(delete("/shifts/{shiftId}", shiftId)
                .header(TestUserAuthenticationFilter.HEADER, store.manager().userId())
                .param("expected_version", String.valueOf(version)));
    }

    private SubstituteRequest request(long requestId) {
        return requestRepository.findById(requestId).orElseThrow();
    }

    private RequestEvent lastEvent(long requestId) {
        return eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).getLast();
    }

    private int eventCount(long requestId) {
        return eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).size();
    }

    private String response(long requestId, long targetMembershipId) {
        return jdbcTemplate.queryForObject(
                "select response from request_recipients where request_id = ? and target_membership_id = ?",
                String.class, requestId, targetMembershipId);
    }

    private List<Long> memberLeftRecipients(long requestId) {
        return jdbcTemplate.queryForList(
                "select recipient_membership_id from notifications where request_id = ? and kind = 'MEMBER_LEFT'",
                Long.class, requestId);
    }

    private int notificationCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from notifications where request_id = ?",
                Integer.class, requestId);
    }

    private int notificationCountFor(long membershipId) {
        return jdbcTemplate.queryForObject("select count(*) from notifications where recipient_membership_id = ?",
                Integer.class, membershipId);
    }
}
