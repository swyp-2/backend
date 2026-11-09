package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 근무에 서로 다른 Idempotency-Key로 동시에 요청을 보내면 1건만 만들어지고 나머지는 409인지 확인.
 */
@IntegrationTest
class SubstituteRequestCreateConcurrencyTest {

    private static final int THREADS = 6;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired Clock clock;

    @Test
    void 같은_근무에_동시에_요청하면_한_건만_성공한다() throws Exception {
        SubstituteApi api = new SubstituteApi(mockMvc, jsonMapper,
                new OnboardingApi(mockMvc, jsonMapper, userRepository), new StoreApi(mockMvc, jsonMapper),
                shiftRepository, clock);
        StoreFixture store = api.store(2);
        long requesterUserId = store.crew(0).userId();
        long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 1);
        List<Long> targets = List.of(store.crew(1).membershipId());

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                statuses.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return api.create(requesterUserId, shiftId, targets, UUID.randomUUID().toString())
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnly(200, 409);
            assertThat(results).filteredOn(code -> code == 200).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(requestRepository.findAll().stream()
                .filter(r -> r.getShiftId().equals(shiftId))
                .count()).isEqualTo(1);
    }
}
