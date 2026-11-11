package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * 근무 시작 시각에 도달했지만 스케줄러가 아직 EXPIRED로 바꾸기 전: 6개 행동 모두 REQUEST_EXPIRED(2026-11-10 결정).
 */
@IntegrationTest
class SubstituteRequestTransitionExpiryApiTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

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
    @Autowired MutableClock clock;

    private SubstituteApi api;
    private StoreFixture store;
    private long waitingRequest;
    private long pendingRequest;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(2);
        long requester = store.crew(0).membershipId();
        List<Long> targets = List.of(store.crew(1).membershipId());
        waitingRequest = api.createRequest(store.crew(0).userId(), api.shift(store.storeId(), requester, 0), targets)
                .get("id").asLong();
        pendingRequest = api.createRequest(store.crew(0).userId(), api.shift(store.storeId(), requester, 0), targets)
                .get("id").asLong();
        api.transitionOk(store.crew(1).userId(), pendingRequest, "accept");

        clock.advance(Duration.ofDays(1));
    }

    @Test
    void 시작_시각이_지나면_수락_거절은_REQUEST_EXPIRED() throws Exception {
        expectExpired(store.crew(1).userId(), waitingRequest, "accept", "WAITING");
        expectExpired(store.crew(1).userId(), waitingRequest, "reject", "WAITING");
    }

    @Test
    void 시작_시각이_지나면_취소_철회는_REQUEST_EXPIRED() throws Exception {
        expectExpired(store.crew(0).userId(), waitingRequest, "cancel", "WAITING");
        expectExpired(store.crew(1).userId(), pendingRequest, "withdraw", "PENDING_MANAGER");
    }

    @Test
    void 시작_시각이_지나면_승인_최종거절은_REQUEST_EXPIRED이고_담당자는_그대로() throws Exception {
        expectExpired(store.manager().userId(), pendingRequest, "approve", "PENDING_MANAGER");
        expectExpired(store.manager().userId(), pendingRequest, "final-reject", "PENDING_MANAGER");

        assertThat(api.data(api.detail(store.manager().userId(), pendingRequest)).get("status").asText())
                .isEqualTo("PENDING_MANAGER");
    }

    private void expectExpired(long userId, long requestId, String action, String currentState) throws Exception {
        api.transition(userId, requestId, action)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_EXPIRED"))
                .andExpect(jsonPath("$.current_state").value(currentState));
    }
}
