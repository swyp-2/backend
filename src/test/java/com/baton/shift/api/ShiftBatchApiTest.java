package com.baton.shift.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.Clock;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ShiftBatchApiTest {

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
    @Autowired Clock injectedClock;

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

    @Test
    void 주입되는_Clock은_테스트_시계다() {
        assertThat(injectedClock).isSameAs(clock);
    }

    // ---- 미리보기 ----

    @Test
    void 미리보기는_아무것도_쓰지_않는다() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long b = membershipOf(crewNamed("나래"));
        shift(a, kst(2026, 11, 11, 9, 0), Duration.ofHours(4));
        long[] before = counts();

        preview(managerUser, exampleBody(a, b)).andExpect(status().isOk());
        preview(managerUser, exampleBody(a, b)).andExpect(status().isOk());

        assertThat(counts()).containsExactly(before);
    }

    @Test
    void 문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다() throws Exception {
        long crewA = crewNamed("하윤");
        long crewB = crewNamed("나래");
        long a = membershipOf(crewA);
        long b = membershipOf(crewB);
        long dupA = shift(a, kst(2026, 11, 11, 9, 0), Duration.ofHours(4));
        long dupB = shift(b, kst(2026, 11, 13, 9, 0), Duration.ofHours(4));
        // 일부만 겹치는 근무는 중복이 아니다
        shift(a, kst(2026, 11, 16, 10, 0), Duration.ofHours(4));

        JsonNode preview = data(preview(managerUser, exampleBody(a, b)).andExpect(status().isOk()));
        assertThat(preview.get("create_count").asInt()).isEqualTo(26);
        assertThat(preview.get("duplicate_count").asInt()).isEqualTo(2);
        assertThat(preview.get("total_count").asInt()).isEqualTo(28);
        assertThat(preview.get("preview_token").asString()).isNotBlank();
        JsonNode items = preview.get("items");
        assertThat(items).hasSize(28);
        JsonNode first = items.get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder(
                "membership_id", "display_name", "local_date", "starts_at", "ends_at", "duplicate");
        assertThat(first.get("membership_id").asLong()).isEqualTo(Math.min(a, b));
        assertThat(first.get("local_date").asString()).isEqualTo("2026-11-11");
        assertThat(first.get("starts_at").asString()).isEqualTo("2026-11-11T00:00:00Z");
        assertThat(first.get("ends_at").asString()).isEqualTo("2026-11-11T04:00:00Z");
        List<String> duplicates = new ArrayList<>();
        items.forEach(item -> {
            if (item.get("duplicate").asBoolean()) {
                duplicates.add(item.get("display_name").asString() + "@" + item.get("local_date").asString());
            }
        });
        assertThat(duplicates).containsExactlyInAnyOrder("하윤@2026-11-11", "나래@2026-11-13");

        JsonNode created = data(create(managerUser, withToken(exampleBody(a, b), preview), newKey())
                .andExpect(status().isOk()));
        assertThat(created.propertyNames()).containsExactlyInAnyOrder("created_count", "duplicate_count", "shift_ids");
        assertThat(created.get("created_count").asInt()).isEqualTo(26);
        assertThat(created.get("duplicate_count").asInt()).isEqualTo(2);
        List<Long> shiftIds = longs(created.get("shift_ids"));
        assertThat(shiftIds).hasSize(26).doesNotContain(dupA, dupB).doesNotHaveDuplicates();
        assertThat(liveShiftCount()).isEqualTo(28 + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shift_events WHERE event_kind = 'CREATED' AND actor_membership_id = ?",
                Long.class, managerMembership)).isEqualTo(26);

        // 6단계 월 조회: 별도 게시·수락 단계 없이 바로 반영
        JsonNode novA = data(month(crewA, "2026-11", "mine").andExpect(status().isOk()));
        assertThat(novA.get("shifts")).hasSize(9 + 1);
        JsonNode decA = data(month(crewA, "2026-12", "mine").andExpect(status().isOk()));
        assertThat(decA.get("shifts")).hasSize(5);
        JsonNode novB = data(month(crewB, "2026-11", "mine").andExpect(status().isOk()));
        assertThat(novB.get("shifts")).hasSize(9);
        JsonNode novAll = data(month(managerUser, "2026-11", "all").andExpect(status().isOk()));
        assertThat(dayCounts(novAll)).containsExactly(
                "2026-11-11=2", "2026-11-13=2", "2026-11-16=2", "2026-11-18=2", "2026-11-20=2",
                "2026-11-23=2", "2026-11-25=2", "2026-11-27=2", "2026-11-30=2");
        JsonNode decAll = data(month(managerUser, "2026-12", "all").andExpect(status().isOk()));
        assertThat(dayCounts(decAll)).containsExactly(
                "2026-12-02=2", "2026-12-04=2", "2026-12-07=2", "2026-12-09=2", "2026-12-11=2");
    }

    // ---- 멱등성 ----

    @Test
    void 같은_키로_두_번_보내면_처음_결과_그대로이고_52건이_되지_않는다() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long b = membershipOf(crewNamed("나래"));
        shift(a, kst(2026, 11, 11, 9, 0), Duration.ofHours(4));
        shift(b, kst(2026, 11, 13, 9, 0), Duration.ofHours(4));
        Map<String, Object> body = withToken(exampleBody(a, b), data(preview(managerUser, exampleBody(a, b))));
        String key = newKey();

        JsonNode first = data(create(managerUser, body, key).andExpect(status().isOk()));
        JsonNode second = data(create(managerUser, body, key).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(second.get("created_count").asInt()).isEqualTo(26);
        assertThat(liveShiftCount()).isEqualTo(28);

        // 31분 뒤 재시도도 토큰 만료와 관계없이 처음 결과
        clock.advance(Duration.ofMinutes(31));
        assertThat(data(create(managerUser, body, key).andExpect(status().isOk()))).isEqualTo(first);

        Map<String, Object> other = new LinkedHashMap<>(body);
        other.put("ends_at", "2026-11-11T05:00:00Z");
        create(managerUser, other, key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(liveShiftCount()).isEqualTo(28);
    }

    @Test
    void Idempotency_Key가_없으면_422() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = withToken(singleBody(a), data(preview(managerUser, singleBody(a))));

        create(managerUser, body, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("Idempotency-Key"));
        assertThat(liveShiftCount()).isZero();
    }

    // ---- 미리보기 이후 변경 ----

    @Test
    void 미리보기_후_다른_매니저가_그중_1건을_등록하면_PREVIEW_STALE이고_저장_0건() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long b = membershipOf(crewNamed("나래"));
        long otherManager = secondManager();
        Map<String, Object> body = withToken(exampleBody(a, b), data(preview(managerUser, exampleBody(a, b))));

        Map<String, Object> one = singleBody(b);
        one.put("starts_at", "2026-11-13T00:00:00Z");
        one.put("ends_at", "2026-11-13T04:00:00Z");
        create(otherManager, withToken(one, data(preview(otherManager, one))), newKey()).andExpect(status().isOk());
        assertThat(liveShiftCount()).isEqualTo(1);

        create(managerUser, body, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));
        assertThat(liveShiftCount()).isEqualTo(1);
        assertThat(eventCount()).isEqualTo(1);

        // 다시 미리보기하면 27건 등록
        JsonNode again = data(preview(managerUser, exampleBody(a, b)));
        assertThat(again.get("create_count").asInt()).isEqualTo(27);
        create(managerUser, withToken(exampleBody(a, b), again), newKey()).andExpect(status().isOk());
        assertThat(liveShiftCount()).isEqualTo(28);
    }

    @Test
    void 미리보기_후_중복이던_근무가_삭제돼도_PREVIEW_STALE() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long dup = shift(a, kst(2026, 11, 11, 9, 0), Duration.ofHours(4));
        Map<String, Object> body = exampleBody(a);
        body = withToken(body, data(preview(managerUser, body)));

        jdbcTemplate.update("UPDATE shifts SET deleted_at = now() WHERE id = ?", dup);

        create(managerUser, body, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));
        assertThat(liveShiftCount()).isZero();
    }

    @Test
    void 토큰_변조_만료_다른_입력_다른_매니저면_PREVIEW_STALE() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = withToken(singleBody(a), data(preview(managerUser, singleBody(a))));
        String token = (String) body.get("preview_token");

        Map<String, Object> tampered = new LinkedHashMap<>(body);
        char last = token.charAt(token.length() - 1);
        tampered.put("preview_token", token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A'));
        expectStale(create(managerUser, tampered, newKey()));

        Map<String, Object> garbage = new LinkedHashMap<>(body);
        garbage.put("preview_token", "not-a-token");
        expectStale(create(managerUser, garbage, newKey()));

        Map<String, Object> otherInput = new LinkedHashMap<>(body);
        otherInput.put("ends_at", "2026-11-11T05:00:00Z");
        expectStale(create(managerUser, otherInput, newKey()));

        long otherManager = secondManager();
        expectStale(create(otherManager, body, newKey()));

        clock.advance(Duration.ofMinutes(31));
        expectStale(create(managerUser, body, newKey()));

        assertThat(liveShiftCount()).isZero();
    }

    @Test
    void 토큰은_30분까지_유효하다() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = withToken(singleBody(a), data(preview(managerUser, singleBody(a))));

        clock.advance(Duration.ofMinutes(30));
        create(managerUser, body, newKey()).andExpect(status().isOk());
        assertThat(liveShiftCount()).isEqualTo(1);
    }

    @Test
    void 토큰이_없으면_422() throws Exception {
        long a = membershipOf(crewNamed("하윤"));

        create(managerUser, singleBody(a), newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("preview_token"));
    }

    // ---- 권한·구성원 ----

    @Test
    void 크루원은_미리보기도_등록도_403() throws Exception {
        long crew = crewNamed("하윤");
        long a = membershipOf(crew);
        Map<String, Object> body = withToken(singleBody(a), data(preview(managerUser, singleBody(a))));

        preview(crew, singleBody(a))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        create(crew, body, newKey()).andExpect(status().isForbidden());
        assertThat(liveShiftCount()).isZero();
    }

    @Test
    void 다른_매장_매니저는_본문_store_id로_대상을_지정해도_403() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long outsider = userNamed("남매니저", "MANAGER");
        api.createStore(outsider, "다른 매장");

        preview(outsider, singleBody(a)).andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장_구성원이나_나간_구성원이_있으면_422() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long outsider = userNamed("남매니저", "MANAGER");
        long foreign = api.createStore(outsider, "다른 매장").get("membership_id").asLong();

        Map<String, Object> body = exampleBody(a, foreign);
        preview(managerUser, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("assignee_membership_ids"));

        // 미리보기 뒤 담당자가 나가면 최종 등록도 422
        long b = membershipOf(crewNamed("나래"));
        Map<String, Object> ok = withToken(exampleBody(a, b), data(preview(managerUser, exampleBody(a, b))));
        jdbcTemplate.update("UPDATE memberships SET state = 'LEFT', left_at = now() WHERE id = ?", b);
        create(managerUser, ok, newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("assignee_membership_ids"));
        assertThat(liveShiftCount()).isZero();
    }

    @Test
    void 매니저_본인도_근무자로_등록할_수_있다() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = exampleBody(a, managerMembership);

        JsonNode preview = data(preview(managerUser, body).andExpect(status().isOk()));
        assertThat(preview.get("create_count").asInt()).isEqualTo(28);
        JsonNode created = data(create(managerUser, withToken(body, preview), newKey()).andExpect(status().isOk()));

        assertThat(created.get("created_count").asInt()).isEqualTo(28);
        assertThat(data(month(managerUser, "2026-11", "mine")).get("shifts")).hasSize(9);
    }

    @Test
    void 입력이_잘못되면_미리보기도_422() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = singleBody(a);
        body.put("starts_at", "2026-11-11T00:00:30Z");

        preview(managerUser, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("starts_at"));
    }

    // ---- 전부 중복 ----

    @Test
    void 전부_중복이면_미리보기는_0건으로_보여주고_최종_등록은_422() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        Map<String, Object> body = singleBody(a);
        shift(a, kst(2026, 11, 11, 9, 0), Duration.ofHours(4));

        JsonNode preview = data(preview(managerUser, body).andExpect(status().isOk()));
        assertThat(preview.get("create_count").asInt()).isZero();
        assertThat(preview.get("duplicate_count").asInt()).isEqualTo(1);
        assertThat(preview.get("items").get(0).get("duplicate").asBoolean()).isTrue();
        long[] before = counts();

        create(managerUser, withToken(body, preview), newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("모두 이미 등록된 근무예요."));
        assertThat(counts()).containsExactly(before);
    }

    // ---- 동시성 ----

    @Test
    void 겹치는_계획을_동시에_최종_등록하면_한쪽만_성공하고_부분_저장이_없다() throws Exception {
        long a = membershipOf(crewNamed("하윤"));
        long b = membershipOf(crewNamed("나래"));
        long c = membershipOf(crewNamed("다온"));
        int rounds = 5;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                String startsAt = "2026-11-%02dT00:00:00Z".formatted(11 + round);
                String endsAt = "2026-11-%02dT04:00:00Z".formatted(11 + round);
                Map<String, Object> x = singleBody(a, b);
                Map<String, Object> y = singleBody(b, c);
                for (Map<String, Object> body : List.of(x, y)) {
                    body.put("starts_at", startsAt);
                    body.put("ends_at", endsAt);
                }
                Map<String, Object> bodyX = withToken(x, data(preview(managerUser, x)));
                Map<String, Object> bodyY = withToken(y, data(preview(managerUser, y)));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (Map<String, Object> body : List.of(bodyX, bodyY)) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        go.await();
                        return create(managerUser, body, newKey()).andReturn().getResponse().getStatus();
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<Integer> results = new ArrayList<>();
                for (Future<Integer> f : futures) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(results).containsExactlyInAnyOrder(200, 409);
            }
        } finally {
            executor.shutdownNow();
        }
        // 라운드마다 정확히 한 계획(2건)만 저장
        assertThat(liveShiftCount()).isEqualTo(rounds * 2L);
        assertThat(eventCount()).isEqualTo(rounds * 2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM (
                    SELECT store_id, assignee_membership_id, starts_at, ends_at FROM shifts
                    WHERE deleted_at IS NULL GROUP BY 1, 2, 3, 4 HAVING count(*) > 1) d
                """, Long.class)).isZero();
    }

    // ---- helpers ----

    /** API 문서 4절 예시: 11-11 09:00~13:00 KST, 11-11~12-11 월수금. */
    private Map<String, Object> exampleBody(long... assignees) {
        Map<String, Object> body = singleBody(assignees);
        Map<String, Object> repeat = new LinkedHashMap<>();
        repeat.put("enabled", true);
        repeat.put("start_date", "2026-11-11");
        repeat.put("end_date", "2026-12-11");
        repeat.put("weekdays", List.of(1, 3, 5));
        body.put("repeat", repeat);
        return body;
    }

    private Map<String, Object> singleBody(long... assignees) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("store_id", storeId);
        List<Long> ids = new ArrayList<>();
        for (long id : assignees) {
            ids.add(id);
        }
        body.put("assignee_membership_ids", ids);
        body.put("starts_at", "2026-11-11T00:00:00Z");
        body.put("ends_at", "2026-11-11T04:00:00Z");
        return body;
    }

    private static Map<String, Object> withToken(Map<String, Object> body, JsonNode preview) {
        Map<String, Object> result = new LinkedHashMap<>(body);
        result.put("preview_token", preview.get("preview_token").asString());
        return result;
    }

    private ResultActions preview(long userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/shift-batches/preview")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private ResultActions create(long userId, Map<String, Object> body, String key) throws Exception {
        MockHttpServletRequestBuilder request = post("/shift-batches")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions month(long userId, String month, String scope) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .param("month", month).param("scope", scope));
    }

    private static void expectStale(ResultActions result) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
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

    /** 매장에 두 번째 매니저 소속을 만든다(매니저 합류 API가 없어 리포지토리로 직접). user id를 돌려준다. */
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

    private long liveShiftCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE store_id = ? AND deleted_at IS NULL",
                Long.class, storeId);
    }

    private long eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shift_events e JOIN shifts s ON s.id = e.shift_id WHERE s.store_id = ?",
                Long.class, storeId);
    }

    /** shifts, shift_events, idempotency_records 전체 행 수. */
    private long[] counts() {
        return new long[] {
                jdbcTemplate.queryForObject("SELECT count(*) FROM shifts", Long.class),
                jdbcTemplate.queryForObject("SELECT count(*) FROM shift_events", Long.class),
                jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_records", Long.class)};
    }

    private JsonNode data(ResultActions result) throws Exception {
        return api.data(result);
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asLong()));
        return result;
    }

    private static List<String> dayCounts(JsonNode monthData) {
        List<String> result = new ArrayList<>();
        monthData.get("days").forEach(d -> result.add(d.get("date").asString() + "=" + d.get("headcount").asLong()));
        return result;
    }
}
