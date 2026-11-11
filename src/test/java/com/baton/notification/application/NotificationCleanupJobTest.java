package com.baton.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

/** 7일 지난 알림 정리. 스케줄링은 test 프로필에서 꺼져 있어 job을 직접 호출한다. */
@IntegrationTest
class NotificationCleanupJobTest {

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
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired NotificationCleanupJob job;
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
    void 생성_후_168시간_전에는_남고_정확히_168시간이면_삭제된다() throws Exception {
        long requestId = createRequest();
        Instant createdAt = notificationCreatedAt(requestId);

        clock.set(createdAt.plus(Duration.ofHours(168)).minus(1, ChronoUnit.MICROS));
        job.purgeExpired();
        assertThat(notificationCount(requestId)).isEqualTo(1);

        clock.set(createdAt.plus(Duration.ofHours(168)));
        job.purgeExpired();
        assertThat(notificationCount(requestId)).isZero();
    }

    @Test
    void 알림을_지워도_요청과_진행_기록은_남는다() throws Exception {
        long requestId = createRequest();
        int eventsBefore = eventCount(requestId);

        clock.advance(Duration.ofDays(8));
        job.purgeExpired();

        assertThat(notificationCount(requestId)).isZero();
        assertThat(requestRepository.findById(requestId)).isPresent();
        assertThat(eventCount(requestId)).isEqualTo(eventsBefore);
    }

    @Test
    void 보관_중인_새_알림은_지우지_않는다() throws Exception {
        long oldRequest = createRequest();
        clock.advance(Duration.ofDays(7));
        long newRequest = createRequest();

        job.purgeExpired();

        assertThat(notificationCount(oldRequest)).isZero();
        assertThat(notificationCount(newRequest)).isEqualTo(1);
        assertThat(requestRepository.findById(newRequest).orElseThrow().getStatus())
                .isEqualTo(SubstituteStatus.WAITING);
    }

    /** 지정 대상 1명에게 새 요청 알림 1건이 생긴다. */
    private long createRequest() throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 10);
        return api.createRequest(store.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                .get("id").asLong();
    }

    private Instant notificationCreatedAt(long requestId) {
        return jdbcTemplate.queryForObject("select created_at from notifications where request_id = ?",
                OffsetDateTime.class, requestId).toInstant();
    }

    private int notificationCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from notifications where request_id = ?",
                Integer.class, requestId);
    }

    private int eventCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from request_events where request_id = ?",
                Integer.class, requestId);
    }
}
