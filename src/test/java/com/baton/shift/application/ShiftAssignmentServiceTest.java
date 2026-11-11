package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.ShiftSnapshot;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ShiftAssignment 실제 구현(B의 승인 트랜잭션에서 호출됨). */
@IntegrationTest
class ShiftAssignmentServiceTest {

    @Autowired ShiftAssignment shiftAssignment;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    OnboardingApi onboarding;
    StoreApi api;
    long managerUser;
    long storeId;
    String code;
    long fromId;
    long toId;
    long shiftId;
    Instant start;

    @BeforeEach
    void setUp() throws Exception {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
        managerUser = userNamed("최매니저", "MANAGER");
        JsonNode store = api.createStore(managerUser, "배정 카페");
        storeId = store.get("store_id").asLong();
        code = store.get("invite_code").asString();
        fromId = membershipOf(crew("하윤"));
        toId = membershipOf(crew("나래"));
        start = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        shiftId = shiftRepository.saveAndFlush(Shift.schedule(storeId, fromId, start, start.plus(Duration.ofHours(4)),
                Instant.now())).getId();
    }

    @Test
    void 주입되는_구현은_A의_실제_구현이다() {
        assertThat(shiftAssignment).isInstanceOf(ShiftAssignmentService.class);
    }

    // ---- reassign ----

    @Test
    void 담당자를_바꾸고_버전을_올리고_REASSIGNED를_기록한다() throws Exception {
        long versionInTx = transactionTemplate.execute(status -> {
            shiftAssignment.reassign(shiftId, fromId, toId, 0L);
            // 호출자는 같은 트랜잭션에서 바뀐 version을 본다
            return shiftAssignment.lockSnapshot(shiftId).version();
        });
        assertThat(versionInTx).isEqualTo(1L);

        ShiftSnapshot snapshot = shiftAssignment.getSnapshot(shiftId);
        assertThat(snapshot.assigneeMembershipId()).isEqualTo(toId);
        assertThat(snapshot.version()).isEqualTo(1L);

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_kind, before_value::text AS b, after_value::text AS a, actor_membership_id, request_id "
                        + "FROM shift_events WHERE shift_id = ?", shiftId);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("event_kind")).isEqualTo("REASSIGNED");
        assertThat(jsonMapper.readTree((String) event.get("b")).get("assignee_membership_id").asLong())
                .isEqualTo(fromId);
        assertThat(jsonMapper.readTree((String) event.get("a")).get("assignee_membership_id").asLong())
                .isEqualTo(toId);
        assertThat(event.get("actor_membership_id")).isNull();
        assertThat(event.get("request_id")).isNull();
    }

    @Test
    void 버전이_다르면_SHIFT_STATE_CONFLICT() {
        assertConflict(() -> reassignInTx(fromId, toId, 5L), "CHANGED");
        assertUnchanged();
    }

    @Test
    void 삭제된_근무면_SHIFT_STATE_CONFLICT() {
        jdbcTemplate.update("UPDATE shifts SET deleted_at = now(), version = 1 WHERE id = ?", shiftId);

        assertConflict(() -> reassignInTx(fromId, toId, 1L), "DELETED");
        assertThat(shiftAssignment.getSnapshot(shiftId).assigneeMembershipId()).isEqualTo(fromId);
        assertThat(reassignEventCount()).isZero();
    }

    @Test
    void 현재_담당자가_from이_아니면_SHIFT_STATE_CONFLICT() {
        assertConflict(() -> reassignInTx(toId, fromId, 0L), "CHANGED");
        assertUnchanged();
    }

    @Test
    void 새_담당자가_매장을_나갔거나_from과_같으면_SHIFT_STATE_CONFLICT() {
        assertConflict(() -> reassignInTx(fromId, fromId, 0L), "CHANGED");
        Membership to = membershipRepository.findById(toId).orElseThrow();
        to.leave(Instant.now());
        membershipRepository.saveAndFlush(to);

        assertConflict(() -> reassignInTx(fromId, toId, 0L), "CHANGED");
        assertUnchanged();
    }

    @Test
    void 호출자_트랜잭션_없이_reassign이나_lockSnapshot을_부르면_예외() {
        assertThatThrownBy(() -> shiftAssignment.reassign(shiftId, fromId, toId, 0L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> shiftAssignment.lockSnapshot(shiftId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertUnchanged();
    }

    // ---- getSnapshot ----

    @Test
    void 스냅샷은_현재_상태를_돌려주고_삭제된_근무도_deleted_true로_돌려준다() {
        assertThat(shiftAssignment.getSnapshot(shiftId)).isEqualTo(
                new ShiftSnapshot(shiftId, storeId, fromId, start, start.plus(Duration.ofHours(4)), 0L, false));

        jdbcTemplate.update("UPDATE shifts SET deleted_at = now(), version = 1 WHERE id = ?", shiftId);
        ShiftSnapshot deleted = shiftAssignment.getSnapshot(shiftId);
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.version()).isEqualTo(1L);
    }

    @Test
    void 없는_근무는_NOT_FOUND() {
        assertError(() -> shiftAssignment.getSnapshot(Long.MAX_VALUE), ErrorCode.NOT_FOUND);
        assertError(() -> transactionTemplate.execute(status -> shiftAssignment.lockSnapshot(Long.MAX_VALUE)),
                ErrorCode.NOT_FOUND);
    }

    // ---- 동시성 ----

    @Test
    void 삭제와_담당자_변경이_동시에_일어나면_하나만_성공한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean deleteWon;
        boolean reassignWon;
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> deleteResult = executor.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(delete("/shifts/{shiftId}", shiftId)
                                .header(TestUserAuthenticationFilter.HEADER, managerUser)
                                .param("expected_version", "0"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Boolean> reassignResult = executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    reassignInTx(fromId, toId, 0L);
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIFT_STATE_CONFLICT);
                    return false;
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int deleteStatus = deleteResult.get(30, TimeUnit.SECONDS);
            assertThat(deleteStatus).isIn(200, 409);
            deleteWon = deleteStatus == 200;
            reassignWon = reassignResult.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(deleteWon ^ reassignWon).as("정확히 하나만 성공").isTrue();
        ShiftSnapshot after = shiftAssignment.getSnapshot(shiftId);
        assertThat(after.version()).isEqualTo(1L);
        if (deleteWon) {
            assertThat(after.deleted()).isTrue();
            assertThat(after.assigneeMembershipId()).isEqualTo(fromId);
        } else {
            assertThat(after.deleted()).isFalse();
            assertThat(after.assigneeMembershipId()).isEqualTo(toId);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shift_events WHERE shift_id = ?", Long.class,
                shiftId)).isEqualTo(1L);
    }

    // ---- helpers ----

    private void reassignInTx(long from, long to, long expectedVersion) {
        transactionTemplate.executeWithoutResult(status -> shiftAssignment.reassign(shiftId, from, to, expectedVersion));
    }

    private void assertUnchanged() {
        ShiftSnapshot snapshot = shiftAssignment.getSnapshot(shiftId);
        assertThat(snapshot.assigneeMembershipId()).isEqualTo(fromId);
        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.deleted()).isFalse();
        assertThat(reassignEventCount()).isZero();
    }

    private long reassignEventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shift_events WHERE shift_id = ? AND event_kind = 'REASSIGNED'", Long.class,
                shiftId);
    }

    private static void assertConflict(ThrowingCallable call, String currentState) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SHIFT_STATE_CONFLICT);
            assertThat(e.getCurrentState()).isEqualTo(currentState);
        });
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private long userNamed(String name, String role) throws Exception {
        long userId = onboarding.newUser();
        onboarding.agree(userId, onboarding.allAgreements(userId, true))
                .andExpect(MockMvcResultMatchers.status().isOk());
        onboarding.setName(userId, name).andExpect(MockMvcResultMatchers.status().isOk());
        onboarding.selectRole(userId, role).andExpect(MockMvcResultMatchers.status().isOk());
        return userId;
    }

    private long crew(String name) throws Exception {
        long userId = userNamed(name, "CREW");
        api.join(userId, code).andExpect(MockMvcResultMatchers.status().isOk());
        return userId;
    }

    private long membershipOf(long userId) {
        return membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).orElseThrow().getId();
    }
}
