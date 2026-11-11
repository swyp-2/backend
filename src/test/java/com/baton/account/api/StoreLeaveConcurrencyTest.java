package com.baton.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.account.api.AccountFixture.StoreInfo;
import com.baton.account.application.AccountService;
import com.baton.auth.application.AccessTokenService;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.ShiftAssignment;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 나가기와 B 승인(lockActiveById로 같은 membership 행 잠금)이 동시에 일어나면 직렬화되어
 * 먼저 확정된 쪽의 결과를 다른 쪽이 본다.
 */
@IntegrationTest
class StoreLeaveConcurrencyTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AccessTokenService accessTokenService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MembershipQuery membershipQuery;
    @Autowired ShiftAssignment shiftAssignment;
    @Autowired AccountService accountService;

    AccountFixture fx;
    StoreInfo store;
    ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AccountFixture(mockMvc, jsonMapper, userRepository, membershipRepository, shiftRepository,
                accessTokenService);
        long manager = fx.userNamed("최매니저", "MANAGER");
        store = fx.storeOf(manager, "동시 카페");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void 승인이_먼저_소속을_잠그고_근무를_넘기면_나가기는_기다렸다가_409() throws Exception {
        long leaver = fx.userNamed("하윤", "CREW");
        long leaverMembership = fx.join(leaver, store.code());
        long requester = fx.userNamed("도연", "CREW");
        long requesterMembership = fx.join(requester, store.code());
        Instant start = Instant.now().plus(Duration.ofDays(2));
        long shiftId = fx.shift(store.storeId(), requesterMembership, start, start.plus(Duration.ofHours(4)));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> approval = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
            Optional<ActiveMembership> candidate = membershipQuery.lockActiveById(leaverMembership);
            assertThat(candidate).isPresent();
            locked.countDown();
            await(release);
            shiftAssignment.reassign(shiftId, requesterMembership, leaverMembership, 0L);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MvcResult> leave = executor.submit(() -> fx.leave(leaver, store.storeId()).andReturn());
        waitUntilBlockedOnLock();
        release.countDown();

        approval.get(30, TimeUnit.SECONDS);
        MvcResult result = leave.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("FUTURE_SHIFT_REMAINS");
        assertThat(membershipState(leaverMembership)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT assignee_membership_id FROM shifts WHERE id = ?",
                Long.class, shiftId)).isEqualTo(leaverMembership);
    }

    @Test
    void 나가기가_먼저_잠그고_확정하면_승인_쪽_lockActiveById는_비활성으로_본다() throws Exception {
        long leaver = fx.userNamed("하윤", "CREW");
        long leaverMembership = fx.join(leaver, store.code());

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> leave = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
            accountService.leave(leaver, store.storeId());
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Optional<ActiveMembership>> approval = executor.submit(() ->
                transactionTemplate.execute(tx -> membershipQuery.lockActiveById(leaverMembership)));
        waitUntilBlockedOnLock();
        release.countDown();

        leave.get(30, TimeUnit.SECONDS);
        assertThat(approval.get(30, TimeUnit.SECONDS)).isEmpty();
        assertThat(membershipState(leaverMembership)).isEqualTo("LEFT");
        fx.onboarding.me(leaver).andExpect(status().isOk());
    }

    /** 다른 세션이 행 잠금을 기다리는 상태가 될 때까지 기다린다. */
    private void waitUntilBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("잠금 대기 상태가 되지 않음");
    }

    private String membershipState(long membershipId) {
        return jdbcTemplate.queryForObject("SELECT state FROM memberships WHERE id = ?", String.class, membershipId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
