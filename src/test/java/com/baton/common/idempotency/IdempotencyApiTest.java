package com.baton.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class IdempotencyApiTest {

    private static final AtomicLong TARGET_IDS = new AtomicLong(1_000);

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired IdempotencyTestController controller;
    @Autowired IdempotencyRecordRepository recordRepository;

    long userId;
    long targetId;
    String key;

    @BeforeEach
    void setUp() {
        userId = userRepository.saveAndFlush(User.signUp()).getId();
        targetId = TARGET_IDS.incrementAndGet();
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        controller.actionDelayMillis = 0;
    }

    @Test
    void 같은_키_같은_요청은_한_번만_실행하고_첫_결과를_그대로_돌려준다() throws Exception {
        JsonNode first = data(call(userId, targetId, key, 10).andExpect(status().isOk()));
        JsonNode second = data(call(userId, targetId, key, 10).andExpect(status().isOk()));

        assertThat(controller.executionCount(targetId)).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(second.get("execution_no").asInt()).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_본문이면_422_IDEMPOTENCY_KEY_REUSED() throws Exception {
        call(userId, targetId, key, 10).andExpect(status().isOk());

        call(userId, targetId, key, 99)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(controller.executionCount(targetId)).isEqualTo(1);
    }

    @Test
    void 같은_키를_다른_대상에_쓰면_422_IDEMPOTENCY_KEY_REUSED() throws Exception {
        long otherTarget = TARGET_IDS.incrementAndGet();
        call(userId, targetId, key, 10).andExpect(status().isOk());

        call(userId, otherTarget, key, 10)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(controller.executionCount(otherTarget)).isZero();
    }

    @Test
    void 다른_사용자는_같은_키를_독립적으로_쓴다() throws Exception {
        long otherUserId = userRepository.saveAndFlush(User.signUp()).getId();

        call(userId, targetId, key, 10).andExpect(status().isOk());
        call(otherUserId, targetId, key, 10).andExpect(status().isOk());

        assertThat(controller.executionCount(targetId)).isEqualTo(2);
    }

    @Test
    void 처리가_실패하면_기록이_남지_않아_같은_키로_다시_실행된다() throws Exception {
        controller.failOnce.add(targetId);

        call(userId, targetId, key, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_STATE_CONFLICT"));
        assertThat(recordRepository.findByUserIdAndScopeAndIdempotencyKey(
                userId, IdempotencyScope.SUBSTITUTE_REQUEST_ACCEPT, key)).isEmpty();

        call(userId, targetId, key, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.execution_no").value(2));
    }

    @Test
    void 키가_없거나_형식이_틀리면_422_VALIDATION_FAILED이고_실행하지_않는다() throws Exception {
        for (String invalid : new String[] {null, "", "has space", "한글키", "a".repeat(101)}) {
            call(userId, targetId, invalid, 10)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field_errors[0].field").value(IdempotencyService.HEADER));
        }
        assertThat(controller.executionCount(targetId)).isZero();
    }

    @Test
    void 기록은_24시간_보존하고_응답_본문을_저장한다() throws Exception {
        call(userId, targetId, key, 10).andExpect(status().isOk());

        IdempotencyRecord record = recordRepository.findByUserIdAndScopeAndIdempotencyKey(
                userId, IdempotencyScope.SUBSTITUTE_REQUEST_ACCEPT, key).orElseThrow();
        assertThat(record.getExpiresAt()).isEqualTo(record.getCreatedAt().plus(IdempotencyService.RETENTION));
        assertThat(jsonMapper.readTree(record.getResponseBody()).get("amount").asInt()).isEqualTo(10);
    }

    @Test
    void 같은_키로_동시에_보내면_한_번만_실행되고_모두_같은_결과를_받는다() throws Exception {
        int threads = 8;
        controller.actionDelayMillis = 300;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call(userId, targetId, key, 10).andReturn().getResponse();
                }));
            }
            ready.await();
            start.countDown();

            List<JsonNode> results = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                results.add(jsonMapper.readTree(response.getContentAsString()).get("data"));
            }

            assertThat(controller.executionCount(targetId)).isEqualTo(1);
            assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
        } finally {
            executor.shutdownNow();
        }
    }

    private ResultActions call(long asUser, long target, String idempotencyKey, int amount) throws Exception {
        var request = post("/test-idempotency/targets/{targetId}", target)
                .header(TestUserAuthenticationFilter.HEADER, asUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}");
        if (idempotencyKey != null) {
            request.header(IdempotencyService.HEADER, idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
