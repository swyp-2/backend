package com.baton.shift.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.Role;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.PersonalColorPalette;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SCH-07 DELETE /shifts/{shift_id}. */
@IntegrationTest
class ShiftDeleteApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

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
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    OnboardingApi onboarding;
    StoreApi api;

    long managerUser;
    long storeId;
    long managerMembership;
    String code;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
        managerUser = userNamed("최매니저", "MANAGER");
        JsonNode store = api.createStore(managerUser, "바톤 카페");
        storeId = store.get("store_id").asLong();
        managerMembership = store.get("membership_id").asLong();
        code = store.get("invite_code").asString();
    }

    // ---- 정상 삭제 ----

    @Test
    void 삭제하면_날짜_조회와_월_조회에서_사라지고_상세는_404() throws Exception {
        long crew = crewNamed("하윤");
        long crewMembership = membershipOf(crew);
        Instant start = kst(2026, 11, 12, 9, 0);
        long shiftId = shift(crewMembership, start, Duration.ofHours(4));
        long keep = shift(crewMembership, kst(2026, 11, 12, 15, 0), Duration.ofHours(2));

        JsonNode body = jsonMapper.readTree(deleteShift(managerUser, shiftId, 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.server_time").exists())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("shift_id", "deleted", "closed_request_id");
        assertThat(body.get("shift_id").asLong()).isEqualTo(shiftId);
        assertThat(body.get("deleted").asBoolean()).isTrue();
        assertThat(body.get("closed_request_id").isNull()).isTrue();

        JsonNode day = data(mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                .header(TestUserAuthenticationFilter.HEADER, managerUser).param("date", "2026-11-12"))
                .andExpect(status().isOk()));
        assertThat(shiftIds(day.get("shifts"))).containsExactly(keep);

        JsonNode mine = data(month(crew, "mine").andExpect(status().isOk()));
        assertThat(shiftIds(mine.get("shifts"))).containsExactly(keep);
        JsonNode all = data(month(managerUser, "all").andExpect(status().isOk()));
        assertThat(all.get("days").get(0).get("headcount").asLong()).isEqualTo(1L);

        detail(managerUser, shiftId).andExpect(status().isNotFound());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version, deleted_at FROM shifts WHERE id = ?", shiftId);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1L);
        assertThat(row.get("deleted_at")).isNotNull();

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_kind, actor_membership_id, request_id, after_value, before_value::text AS before_text "
                        + "FROM shift_events WHERE shift_id = ?", shiftId);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("event_kind")).isEqualTo("DELETED");
        assertThat(((Number) event.get("actor_membership_id")).longValue()).isEqualTo(managerMembership);
        assertThat(event.get("request_id")).isNull();
        assertThat(event.get("after_value")).isNull();
        JsonNode before = jsonMapper.readTree((String) event.get("before_text"));
        assertThat(before.get("assignee_membership_id").asLong()).isEqualTo(crewMembership);
        assertThat(Instant.parse(before.get("starts_at").asString())).isEqualTo(start);
        assertThat(Instant.parse(before.get("ends_at").asString())).isEqualTo(start.plus(Duration.ofHours(4)));
    }

    @Test
    void 삭제한_근무와_같은_담당자_같은_시간으로_다시_등록할_수_있다() throws Exception {
        long crewMembership = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = singleBody(crewMembership, "2026-11-11T00:00:00Z", "2026-11-11T04:00:00Z");
        long shiftId = createdIds(body).getFirst();

        deleteShift(managerUser, shiftId, 0L).andExpect(status().isOk());

        List<Long> again = createdIds(body);
        assertThat(again).hasSize(1).doesNotContain(shiftId);
    }

    @Test
    void 반복_등록한_근무_중_하루를_지워도_다른_날짜는_남는다() throws Exception {
        long crewMembership = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = singleBody(crewMembership, "2026-11-11T00:00:00Z", "2026-11-11T04:00:00Z");
        Map<String, Object> repeat = new LinkedHashMap<>();
        repeat.put("enabled", true);
        repeat.put("start_date", "2026-11-11");
        repeat.put("end_date", "2026-11-13");
        repeat.put("weekdays", List.of(1, 2, 3, 4, 5, 6, 7));
        body.put("repeat", repeat);
        List<Long> ids = createdIds(body);
        assertThat(ids).hasSize(3);

        deleteShift(managerUser, ids.get(1), 0L).andExpect(status().isOk());

        assertThat(liveShiftIds()).containsExactlyInAnyOrder(ids.get(0), ids.get(2));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE id IN (?, ?) AND version = 0",
                Long.class, ids.get(0), ids.get(2))).isEqualTo(2L);
    }

    @Test
    void 과거_근무도_삭제할_수_있다() throws Exception {
        long crewMembership = membershipOf(crewNamed("하윤"));
        long shiftId = shift(crewMembership, NOW.minus(Duration.ofDays(3)), Duration.ofHours(4));

        deleteShift(managerUser, shiftId, 0L).andExpect(status().isOk());

        assertThat(liveShiftIds()).doesNotContain(shiftId);
    }

    // ---- 입력·상태 충돌 ----

    @Test
    void expected_version이_없으면_422() throws Exception {
        long shiftId = shift(membershipOf(crewNamed("하윤")), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));

        mockMvc.perform(delete("/shifts/{shiftId}", shiftId).header(TestUserAuthenticationFilter.HEADER, managerUser))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("expected_version"));
        mockMvc.perform(delete("/shifts/{shiftId}", shiftId).header(TestUserAuthenticationFilter.HEADER, managerUser)
                        .param("expected_version", "abc"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(liveShiftIds()).contains(shiftId);
    }

    @Test
    void 오래된_expected_version이면_409_CHANGED이고_근무는_그대로() throws Exception {
        long shiftId = shift(membershipOf(crewNamed("하윤")), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));
        jdbcTemplate.update("UPDATE shifts SET version = 3 WHERE id = ?", shiftId);

        deleteShift(managerUser, shiftId, 2L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_STATE_CONFLICT"))
                .andExpect(jsonPath("$.current_state").value("CHANGED"));

        assertThat(liveShiftIds()).contains(shiftId);
        assertThat(eventCount()).isZero();
    }

    @Test
    void 이미_삭제된_근무를_다시_삭제하면_409_DELETED() throws Exception {
        long shiftId = shift(membershipOf(crewNamed("하윤")), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));
        deleteShift(managerUser, shiftId, 0L).andExpect(status().isOk());

        deleteShift(managerUser, shiftId, 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_STATE_CONFLICT"))
                .andExpect(jsonPath("$.current_state").value("DELETED"));
        // 최신 version을 보내도 삭제된 근무는 DELETED
        deleteShift(managerUser, shiftId, 1L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.current_state").value("DELETED"));
        assertThat(eventCount()).isEqualTo(1L);
    }

    // ---- 권한 ----

    @Test
    void 크루원은_본인_근무도_삭제할_수_없다() throws Exception {
        long crew = crewNamed("하윤");
        long shiftId = shift(membershipOf(crew), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));

        deleteShift(crew, shiftId, 0L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(liveShiftIds()).contains(shiftId);
    }

    @Test
    void 다른_매장_매니저는_403이고_상태를_알려주지_않는다() throws Exception {
        long shiftId = shift(membershipOf(crewNamed("하윤")), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));
        long otherManager = userNamed("김매니저", "MANAGER");
        api.createStore(otherManager, "다른 카페");

        deleteShift(otherManager, shiftId, 9L)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.current_state").doesNotExist());
        assertThat(liveShiftIds()).contains(shiftId);
    }

    @Test
    void 없는_근무는_404() throws Exception {
        deleteShift(managerUser, Long.MAX_VALUE, 0L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- 동시성 ----

    @Test
    void 매니저_두_명이_같은_근무를_동시에_삭제하면_하나만_성공하고_기록은_1건() throws Exception {
        long secondManager = secondManager();
        long shiftId = shift(membershipOf(crewNamed("하윤")), kst(2026, 11, 12, 9, 0), Duration.ofHours(4));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (long user : List.of(managerUser, secondManager)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return deleteShift(user, shiftId, 0L).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shift_events WHERE shift_id = ? AND event_kind = 'DELETED'",
                Long.class, shiftId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM shifts WHERE id = ?", Long.class, shiftId))
                .isEqualTo(1L);
    }

    // ---- helpers ----

    private ResultActions deleteShift(long userId, long shiftId, long expectedVersion) throws Exception {
        return mockMvc.perform(delete("/shifts/{shiftId}", shiftId)
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .param("expected_version", String.valueOf(expectedVersion)));
    }

    private ResultActions detail(long userId, long shiftId) throws Exception {
        return mockMvc.perform(get("/shifts/{shiftId}", shiftId).header(TestUserAuthenticationFilter.HEADER, userId));
    }

    private ResultActions month(long userId, String scope) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .param("month", "2026-11").param("scope", scope));
    }

    private Map<String, Object> singleBody(long assignee, String startsAt, String endsAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("store_id", storeId);
        body.put("assignee_membership_ids", List.of(assignee));
        body.put("starts_at", startsAt);
        body.put("ends_at", endsAt);
        return body;
    }

    /** 미리보기 → 최종 등록. 만든 근무 id를 돌려준다. */
    private List<Long> createdIds(Map<String, Object> body) throws Exception {
        JsonNode preview = data(post(body, "/shift-batches/preview", null).andExpect(status().isOk()));
        Map<String, Object> withToken = new LinkedHashMap<>(body);
        withToken.put("preview_token", preview.get("preview_token").asString());
        JsonNode created = data(post(withToken, "/shift-batches", UUID.randomUUID().toString())
                .andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>();
        created.get("shift_ids").forEach(node -> ids.add(node.asLong()));
        return ids;
    }

    private ResultActions post(Map<String, Object> body, String path, String key) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .header(TestUserAuthenticationFilter.HEADER, managerUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private long userNamed(String name, String role) throws Exception {
        long userId = onboarding.newUser();
        onboarding.agree(userId, onboarding.allAgreements(userId, true)).andExpect(status().isOk());
        onboarding.setName(userId, name).andExpect(status().isOk());
        onboarding.selectRole(userId, role).andExpect(status().isOk());
        return userId;
    }

    private long crewNamed(String name) throws Exception {
        long userId = userNamed(name, "CREW");
        api.join(userId, code).andExpect(status().isOk());
        return userId;
    }

    /** 매니저 합류 API가 없어 리포지토리로 두 번째 매니저 소속을 만든다. user id를 돌려준다. */
    private long secondManager() throws Exception {
        long userId = userNamed("이매니저", "MANAGER");
        membershipRepository.saveAndFlush(Membership.join(storeId, userId, Role.MANAGER,
                PersonalColorPalette.pick(Map.of()), clock.instant()));
        return userId;
    }

    private long membershipOf(long userId) {
        return membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).orElseThrow().getId();
    }

    private long shift(long membershipId, Instant start, Duration length) {
        return shiftRepository.saveAndFlush(
                Shift.schedule(storeId, membershipId, start, start.plus(length), clock.instant())).getId();
    }

    private static Instant kst(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(SEOUL).toInstant();
    }

    private List<Long> liveShiftIds() {
        return jdbcTemplate.queryForList("SELECT id FROM shifts WHERE store_id = ? AND deleted_at IS NULL",
                Long.class, storeId);
    }

    private long eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shift_events e JOIN shifts s ON s.id = e.shift_id WHERE s.store_id = ?",
                Long.class, storeId);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static List<Long> shiftIds(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("shift_id").asLong()));
        return ids;
    }
}
