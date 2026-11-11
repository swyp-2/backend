package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.SubstituteRequest;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** 시작 시각 도달 시 EXPIRED 스케줄러. 스케줄링은 test 프로필에서 꺼져 있어 job을 직접 호출한다. */
@IntegrationTest
class SubstituteRequestExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");

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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SubstituteRequestExpiryJob job;
    @Autowired SubstituteRequestExpiryService expiryService;
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
    void 시작_시각이_지난_응답대기_승인대기_요청은_EXPIRED가_되고_알림은_만들지_않는다() throws Exception {
        long requester = store.crew(0).membershipId();
        long waitingShift = api.shift(store.storeId(), requester, 0);
        long pendingShift = api.shift(store.storeId(), requester, 0);
        long waiting = createRequest(waitingShift);
        long pending = createRequest(pendingShift);
        api.transitionOk(store.crew(1).userId(), pending, "accept");
        int notificationsBefore = notificationCount(waiting) + notificationCount(pending);

        clock.advance(Duration.ofDays(1));
        job.expireStarted();

        assertExpired(waiting, SubstituteStatus.WAITING);
        assertExpired(pending, SubstituteStatus.PENDING_MANAGER);
        assertThat(notificationCount(waiting) + notificationCount(pending)).isEqualTo(notificationsBefore);
        // 원래 담당자 유지
        assertThat(shiftRepository.findById(waitingShift).orElseThrow().getAssigneeMembershipId()).isEqualTo(requester);
        assertThat(shiftRepository.findById(pendingShift).orElseThrow().getAssigneeMembershipId()).isEqualTo(requester);
        // 상세 화면도 EXPIRED, 처리 버튼 없음
        var detail = api.data(api.detail(store.crew(0).userId(), pending));
        assertThat(detail.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(detail.get("allowed_actions")).isEmpty();
    }

    @Test
    void 시작_1마이크로초_전에는_그대로이고_정확히_시작_시각에_만료된다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), store.crew(0).membershipId(), 0));
        Instant startsAt = requestRepository.findById(requestId).orElseThrow().getSourceStartsAt();

        clock.set(startsAt.minus(1, ChronoUnit.MICROS));
        job.expireStarted();
        assertThat(status(requestId)).isEqualTo(SubstituteStatus.WAITING);

        clock.set(startsAt);
        job.expireStarted();
        assertThat(status(requestId)).isEqualTo(SubstituteStatus.EXPIRED);
    }

    @Test
    void 이미_종료된_요청은_바꾸지_않는다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), store.crew(0).membershipId(), 0));
        api.transitionOk(store.crew(0).userId(), requestId, "cancel");

        clock.advance(Duration.ofDays(1));
        job.expireStarted();

        assertThat(status(requestId)).isEqualTo(SubstituteStatus.CANCELLED);
        assertThat(expiredEvents(requestId)).isEmpty();
    }

    @Test
    void 두_번_실행해도_진행_기록은_한_건이다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), store.crew(0).membershipId(), 0));

        clock.advance(Duration.ofDays(1));
        job.expireStarted();
        job.expireStarted();

        assertThat(expiredEvents(requestId)).hasSize(1);
    }

    @Test
    void 서버_여러_대가_같은_요청을_동시에_만료해도_한_번만_반영된다() throws Exception {
        long requestId = createRequest(api.shift(store.storeId(), store.crew(0).membershipId(), 0));
        clock.advance(Duration.ofDays(1));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    ready.countDown();
                    start.await();
                    return expiryService.expire(requestId);
                };
                results.add(executor.submit(task));
            }
            ready.await();
            start.countDown();
            int changed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    changed++;
                }
            }
            assertThat(changed).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(status(requestId)).isEqualTo(SubstituteStatus.EXPIRED);
        assertThat(expiredEvents(requestId)).hasSize(1);
    }

    private long createRequest(long shiftId) throws Exception {
        return api.createRequest(store.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                .get("id").asLong();
    }

    private void assertExpired(long requestId, SubstituteStatus before) {
        SubstituteRequest request = requestRepository.findById(requestId).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(SubstituteStatus.EXPIRED);
        assertThat(request.getClosedAt()).isEqualTo(clock.instant());
        List<RequestEvent> events = expiredEvents(requestId);
        assertThat(events).hasSize(1);
        RequestEvent event = events.getFirst();
        assertThat(event.getActorMembershipId()).isNull();
        assertThat(event.getBeforeStatus()).isEqualTo(before);
        assertThat(event.getAfterStatus()).isEqualTo(SubstituteStatus.EXPIRED);
        assertThat(event.getOccurredAt()).isEqualTo(clock.instant());
    }

    private SubstituteStatus status(long requestId) {
        return requestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private List<RequestEvent> expiredEvents(long requestId) {
        return eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .filter(e -> e.getKind() == RequestEventKind.REQUEST_EXPIRED)
                .toList();
    }

    private int notificationCount(long requestId) {
        return jdbcTemplate.queryForObject("select count(*) from notifications where request_id = ?",
                Integer.class, requestId);
    }
}
