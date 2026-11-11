package com.baton.shift.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.UserRepository;
import com.baton.notification.application.NotificationCleanupJob;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-02·B19: 승인 요청 알림이 7일 보관 후 지워져도 매니저 근무 상세(SH-02)의 "대타 승인 요청 보기"로 AP-02에 들어갈 수 있다.
 * 근무 상세는 B의 실제 SubstituteRequestLifecycle로 진행 중 요청을 찾는다(목 없음).
 */
@IntegrationTest
class ShiftDetailApprovalEntryTest {

    private static final Instant NOW = Instant.parse("2026-11-11T04:00:00Z");

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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationCleanupJob notificationCleanupJob;
    @Autowired MutableClock clock;

    private SubstituteApi api;
    private StoreFixture store;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(2);
    }

    @Test
    void 승인_요청_알림이_만료돼_지워져도_매니저_근무_상세에서_승인_요청_보기가_남는다() throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 10);
        long requestId = api.createRequest(store.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                .get("id").asLong();
        api.transitionOk(store.crew(1).userId(), requestId, "accept");
        assertThat(managerNotificationCount(requestId)).isPositive();

        clock.advance(Duration.ofDays(8));
        notificationCleanupJob.purgeExpired();
        assertThat(managerNotificationCount(requestId)).isZero();

        mockMvc.perform(get("/shifts/{shiftId}", shiftId)
                        .header(TestUserAuthenticationFilter.HEADER, store.manager().userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_request.request_id").value(requestId))
                .andExpect(jsonPath("$.data.active_request.pending_manager_approval").value(true))
                .andExpect(jsonPath("$.data.allowed_actions[0]").value("delete"))
                .andExpect(jsonPath("$.data.allowed_actions[1]").value("view_approval_request"));
    }

    private int managerNotificationCount(long requestId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications where request_id = ? and recipient_membership_id = ?",
                Integer.class, requestId, store.manager().membershipId());
    }
}
