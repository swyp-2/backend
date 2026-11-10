package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.UserRepository;
import com.baton.notification.domain.NotificationRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 동시 요청: 먼저 확정된 처리 하나만 유효하고, 나머지는 409로 최신 상태를 받는다.
 */
@IntegrationTest
class SubstituteRequestTransitionConcurrencyTest {

    private static final int TARGETS = 5;
    private static final int ROUNDS = 3;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired RequestEventRepository eventRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired Clock clock;

    private SubstituteApi api;
    private StoreFixture store;

    @BeforeEach
    void setUp() throws Exception {
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(TARGETS + 1);
    }

    @Test
    void 여러_명이_동시에_수락하면_한_명만_후보가_된다() throws Exception {
        long requestId = newRequest(allTargets());

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 1; i <= TARGETS; i++) {
            long user = store.crew(i).userId();
            calls.add(() -> statusOf(user, requestId, "accept"));
        }
        List<Integer> results = runConcurrently(calls);

        assertThat(results).containsOnly(200, 409);
        assertThat(results).filteredOn(code -> code == 200).hasSize(1);
        var request = requestRepository.findById(requestId).orElseThrow();
        assertThat(request.getStatus().name()).isEqualTo("PENDING_MANAGER");
        assertThat(eventCount(requestId, "CANDIDATE_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void 승인과_취소가_동시에_오면_하나만_반영되고_담당자는_그_결과와_맞는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 1);
            long requestId = api.createRequest(store.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                    .get("id").asLong();
            api.transitionOk(store.crew(1).userId(), requestId, "accept");

            List<Integer> results = runConcurrently(List.of(
                    () -> statusOf(store.manager().userId(), requestId, "approve"),
                    () -> statusOf(store.crew(0).userId(), requestId, "cancel")));

            assertThat(results).containsExactlyInAnyOrder(200, 409);
            String status = requestRepository.findById(requestId).orElseThrow().getStatus().name();
            long assignee = shiftRepository.findById(shiftId).orElseThrow().getAssigneeMembershipId();
            if (results.get(0) == 200) {
                assertThat(status).isEqualTo("APPROVED");
                assertThat(assignee).isEqualTo(store.crew(1).membershipId());
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(assignee).isEqualTo(store.crew(0).membershipId());
            }
        }
    }

    @Test
    void 승인과_철회가_동시에_오면_하나만_반영된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 1);
            long requestId = api.createRequest(store.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                    .get("id").asLong();
            api.transitionOk(store.crew(1).userId(), requestId, "accept");

            List<Integer> results = runConcurrently(List.of(
                    () -> statusOf(store.manager().userId(), requestId, "approve"),
                    () -> statusOf(store.crew(1).userId(), requestId, "withdraw")));

            assertThat(results).containsExactlyInAnyOrder(200, 409);
            String status = requestRepository.findById(requestId).orElseThrow().getStatus().name();
            assertThat(status).isEqualTo(results.get(0) == 200 ? "APPROVED" : "WITHDRAWN");
        }
    }

    @Test
    void 마지막_거절과_수락이_동시에_오면_ALL_DECLINED와_PENDING_MANAGER_중_하나로만_끝난다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long requestId = newRequest(List.of(store.crew(1).membershipId(), store.crew(2).membershipId()));
            api.transitionOk(store.crew(1).userId(), requestId, "reject");

            // crew(1)은 거절 후 재수락, crew(2)는 마지막 거절
            List<Integer> results = runConcurrently(List.of(
                    () -> statusOf(store.crew(1).userId(), requestId, "accept"),
                    () -> statusOf(store.crew(2).userId(), requestId, "reject")));

            // 거절이 먼저면 ALL_DECLINED라 수락이 409, 수락이 먼저면 PENDING_MANAGER라 거절이 409
            assertThat(results).containsExactlyInAnyOrder(200, 409);
            String status = requestRepository.findById(requestId).orElseThrow().getStatus().name();
            assertThat(status).isEqualTo(results.get(0) == 200 ? "PENDING_MANAGER" : "ALL_DECLINED");
        }
    }

    // ------------------------------------------------------------------------------------------

    private List<Long> allTargets() {
        List<Long> targets = new ArrayList<>();
        for (int i = 1; i <= TARGETS; i++) {
            targets.add(store.crew(i).membershipId());
        }
        return targets;
    }

    private long newRequest(List<Long> targets) throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 1);
        return api.createRequest(store.crew(0).userId(), shiftId, targets).get("id").asLong();
    }

    private int statusOf(long userId, long requestId, String action) throws Exception {
        return api.transition(userId, requestId, action).andReturn().getResponse().getStatus();
    }

    private long eventCount(long requestId, String kind) {
        return eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .filter(e -> Objects.equals(e.getKind().name(), kind))
                .count();
    }

    /** 모든 작업이 준비된 뒤 동시에 출발시키고, 제출 순서대로 HTTP 상태를 돌려준다. */
    private static List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
