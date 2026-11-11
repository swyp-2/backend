package com.baton.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.UserRepository;
import com.baton.notification.domain.NotificationRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
 * 실제 동시 요청: 같은 사람의 탭 진입·카드 확인이 겹쳐도 교착·오류 없이 끝나고,
 * 대타 처리(알림 INSERT)와 겹쳐도 서로 막지 않는다.
 */
@IntegrationTest
class NotificationConcurrencyTest {

    private static final int REQUESTS = 6;
    private static final int ROUNDS = 3;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired Clock clock;

    private SubstituteApi api;
    private StoreFixture store;

    @BeforeEach
    void setUp() throws Exception {
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(REQUESTS + 1);
    }

    @Test
    void 같은_사람의_탭_진입과_여러_카드_확인이_동시에_와도_모두_성공한다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long me = store.crew(0).userId();
            List<Long> requestIds = new ArrayList<>();
            for (int i = 1; i <= REQUESTS; i++) {
                requestIds.add(requestFrom(i, store.crew(0).membershipId()));
            }

            List<Callable<Integer>> calls = new ArrayList<>();
            calls.add(() -> status(api.openRequestTab(me)));
            calls.add(() -> status(api.openRequestTab(me)));
            for (long requestId : requestIds) {
                calls.add(() -> status(api.check(me, requestId)));
            }
            List<Integer> results = runConcurrently(calls);

            assertThat(results).containsOnly(204);
            assertThat(api.hasUnread(me)).isFalse();
            assertThat(api.data(api.list(me, "received", null)).get("items"))
                    .allSatisfy(item -> assertThat(item.get("unchecked").asBoolean()).isFalse());
        }
    }

    @Test
    void 매니저의_탭_진입과_수락이_동시에_와도_막히지_않고_점은_DB와_일치한다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            List<Long> requestIds = new ArrayList<>();
            for (int i = 1; i <= REQUESTS; i++) {
                requestIds.add(requestFrom(i, store.crew(0).membershipId()));
            }
            long manager = store.manager().userId();
            long accepter = store.crew(0).userId();

            List<Callable<Integer>> calls = new ArrayList<>();
            calls.add(() -> status(api.openRequestTab(manager)));
            for (long requestId : requestIds) {
                calls.add(() -> status(api.transition(accepter, requestId, "accept")));
            }
            List<Integer> results = runConcurrently(calls);

            assertThat(results.getFirst()).isEqualTo(204);
            assertThat(results.subList(1, results.size())).containsOnly(200);
            // 진입보다 늦게 저장된 수락 알림만 미읽음으로 남는다. 점은 실제 미읽음 존재와 같아야 한다
            boolean unreadInDb = notificationRepository.existsByRecipientMembershipIdAndReadAtIsNullAndExpiresAtAfter(
                    store.manager().membershipId(), clock.instant());
            assertThat(api.hasUnread(manager)).isEqualTo(unreadInDb);

            api.openRequestTab(manager);
            assertThat(api.hasUnread(manager)).isFalse();
        }
    }

    // ------------------------------------------------------------------------------------------

    /** crew(requesterIndex)가 본인 근무로 target에게 요청을 보낸다. */
    private long requestFrom(int requesterIndex, long targetMembershipId) throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(requesterIndex).membershipId(), 2);
        return api.createRequest(store.crew(requesterIndex).userId(), shiftId, List.of(targetMembershipId))
                .get("id").asLong();
    }

    private static int status(org.springframework.test.web.servlet.ResultActions result) {
        return result.andReturn().getResponse().getStatus();
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
