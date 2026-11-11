package com.baton.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.integration.stub.NoopSubstituteRequestLifecycle;
import com.baton.integration.stub.StoreManagerQueryStub;
import com.baton.member.domain.UserRepository;
import com.baton.shift.application.ShiftAssignmentService;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.application.MembershipGuard;
import com.baton.substitute.application.SubstituteRequestLifecycleService;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A(회원·매장·근무·나가기·탈퇴)와 B(대타 요청)의 실제 빈을 실제 API로 잇는 종단 시나리오.
 * 스텁 없이 integration 인터페이스의 실제 구현이 주입된 상태에서 검증한다.
 *
 * <p>시계는 실제 시각(분 절삭)에서 시작하는 MutableClock. 근무는 일괄 등록 API로 만든다.
 */
@IntegrationTest
class EndToEndScenarioTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String WITHDRAWN_NAME = "탈퇴한 구성원";
    private static final String WITHDRAWN_COLOR = "#B0B4BA";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MINUTES));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AccessTokenService accessTokenService;
    @Autowired ApplicationContext context;
    @Autowired MutableClock clock;

    OnboardingApi onboarding;
    StoreApi stores;
    SubstituteApi requests;

    long managerUser;
    long managerMembership;
    long storeId;
    Crew crewA;
    Crew crewB;
    Crew crewC;
    /** 매니저가 등록한 A의 내일 근무. */
    long shiftId;

    record Crew(String name, long userId, long membershipId) {
    }

    /** 시나리오 1: 매니저 가입 → 매장 생성 → 크루원 코드 참여 → 매니저가 A의 미래 근무 등록. */
    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MINUTES));
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        stores = new StoreApi(mockMvc, jsonMapper);
        requests = new SubstituteApi(mockMvc, jsonMapper, onboarding, stores, shiftRepository, clock);

        managerUser = signUpBySocial("최매니저", "MANAGER");
        JsonNode store = stores.createStore(managerUser, "바톤 카페");
        storeId = store.get("store_id").asLong();
        managerMembership = store.get("membership_id").asLong();
        String code = store.get("invite_code").asString();

        crewA = joinCrew("김하윤", code);
        crewB = joinCrew("이도윤", code);
        crewC = joinCrew("박서연", code);

        shiftId = registerShift(crewA.membershipId(), 1);
    }

    // ---- 0. 빈 구성 ----

    @Test
    void 실제_구현_빈이_주입되고_스텁은_등록되지_않는다() {
        assertThat(implOf(SubstituteRequestLifecycle.class)).isEqualTo(SubstituteRequestLifecycleService.class);
        assertThat(implOf(StoreManagerQuery.class)).isEqualTo(MembershipGuard.class);
        assertThat(implOf(MembershipQuery.class)).isEqualTo(MembershipGuard.class);
        assertThat(implOf(ShiftAssignment.class)).isEqualTo(ShiftAssignmentService.class);

        assertThat(context.containsBean("noopSubstituteRequestLifecycle")).isFalse();
        assertThat(context.containsBean("storeManagerQueryStub")).isFalse();
        assertThat(context.getBeansOfType(NoopSubstituteRequestLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(StoreManagerQueryStub.class)).isEmpty();
    }

    // ---- 1~3. 요청 → 수락 → 승인 ----

    @Test
    void 대타_요청_수락_승인으로_담당자가_B로_바뀐다() throws Exception {
        JsonNode before = shiftDetail(crewA.userId(), shiftId);
        assertThat(before.get("assignee").get("membership_id").asLong()).isEqualTo(crewA.membershipId());
        assertThat(before.get("is_mine").asBoolean()).isTrue();
        assertThat(texts(before.get("allowed_actions"))).contains("request_substitute");
        long versionBefore = before.get("version").asLong();

        // 2. A가 B·C 지정 요청 → B 수락
        long requestId = requests.createRequest(crewA.userId(), shiftId,
                List.of(crewB.membershipId(), crewC.membershipId())).get("id").asLong();
        JsonNode accepted = requests.transitionOk(crewB.userId(), requestId, "accept");
        assertThat(accepted.get("status").asString()).isEqualTo("PENDING_MANAGER");
        assertThat(accepted.get("candidate").get("membership_id").asLong()).isEqualTo(crewB.membershipId());

        // 승인 전: 모두에게 담당자는 여전히 A
        for (long viewer : List.of(crewA.userId(), crewB.userId(), managerUser)) {
            JsonNode detail = shiftDetail(viewer, shiftId);
            assertThat(detail.get("assignee").get("membership_id").asLong()).isEqualTo(crewA.membershipId());
            assertThat(detail.get("assignee").get("display_name").asString()).isEqualTo(crewA.name());
            assertThat(detail.get("version").asLong()).isEqualTo(versionBefore);
        }
        JsonNode managerView = shiftDetail(managerUser, shiftId);
        assertThat(managerView.get("active_request").get("request_id").asLong()).isEqualTo(requestId);
        assertThat(managerView.get("active_request").get("pending_manager_approval").asBoolean()).isTrue();
        assertThat(texts(managerView.get("allowed_actions"))).contains("view_approval_request");

        // 3. 매니저 승인
        JsonNode approved = requests.transitionOk(managerUser, requestId, "approve");
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");

        LocalDate date = shiftDate(shiftId);
        for (long viewer : List.of(crewA.userId(), crewB.userId(), managerUser)) {
            JsonNode detail = shiftDetail(viewer, shiftId);
            assertThat(detail.get("assignee").get("membership_id").asLong()).isEqualTo(crewB.membershipId());
            assertThat(detail.get("assignee").get("display_name").asString()).isEqualTo(crewB.name());
            assertThat(detail.get("assignee").get("is_me").asBoolean()).isEqualTo(viewer == crewB.userId());
            assertThat(detail.get("version").asLong()).isGreaterThan(versionBefore);
            assertThat(detail.get("active_request").isNull()).isTrue();

            JsonNode item = findShift(dayShifts(viewer, date).get("shifts"), shiftId);
            assertThat(item.get("assignee").get("membership_id").asLong()).isEqualTo(crewB.membershipId());
            assertThat(item.get("assignee").get("display_name").asString()).isEqualTo(crewB.name());
        }

        YearMonth month = YearMonth.from(date);
        assertThat(shiftIds(monthShifts(crewB.userId(), month, "mine"))).contains(shiftId);
        assertThat(shiftIds(monthShifts(crewA.userId(), month, "mine"))).doesNotContain(shiftId);
        JsonNode managerAll = monthShifts(managerUser, month, "all");
        assertThat(findDay(managerAll.get("days"), date).get("headcount").asLong()).isEqualTo(1);
    }

    // ---- 4. 진행 중 근무 삭제 ----

    @Test
    void 요청_진행_중_근무를_삭제하면_요청이_종료되고_승인은_409() throws Exception {
        long requestId = requests.createRequest(crewA.userId(), shiftId,
                List.of(crewB.membershipId(), crewC.membershipId())).get("id").asLong();
        requests.transitionOk(crewB.userId(), requestId, "accept");

        long version = shiftDetail(managerUser, shiftId).get("version").asLong();
        JsonNode deleted = data(deleteShift(managerUser, shiftId, version).andExpect(status().isOk()));
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("closed_request_id").asLong()).isEqualTo(requestId);

        JsonNode closed = requests.data(requests.detail(crewA.userId(), requestId).andExpect(status().isOk()));
        assertThat(closed.get("status").asString()).isEqualTo("CANCELLED_SOURCE_CHANGED");
        assertThat(closed.get("terminal_reason").asString()).isEqualTo("SHIFT_DELETED");
        assertThat(closed.get("shift").get("deleted").asBoolean()).isTrue();

        JsonNode error = requests.body(requests.transition(managerUser, requestId, "approve")
                .andExpect(status().isConflict()));
        assertThat(error.get("error").get("code").asString()).isEqualTo("REQUEST_STATE_CONFLICT");

        shiftDetailRaw(managerUser, shiftId).andExpect(status().isNotFound());
    }

    // ---- 5. 승인 대기 중 후보 나가기 ----

    @Test
    void 승인_대기_중_후보가_나가면_WITHDRAWN_CANDIDATE_LEFT_담당자는_A() throws Exception {
        long requestId = requests.createRequest(crewA.userId(), shiftId,
                List.of(crewB.membershipId(), crewC.membershipId())).get("id").asLong();
        requests.transitionOk(crewB.userId(), requestId, "accept");

        leave(crewB.userId()).andExpect(status().isNoContent());

        for (long viewer : List.of(crewA.userId(), managerUser)) {
            JsonNode detail = requests.data(requests.detail(viewer, requestId).andExpect(status().isOk()));
            assertThat(detail.get("status").asString()).isEqualTo("WITHDRAWN");
            assertThat(detail.get("terminal_reason").asString()).isEqualTo("CANDIDATE_LEFT");
        }

        JsonNode shift = shiftDetail(managerUser, shiftId);
        assertThat(shift.get("assignee").get("membership_id").asLong()).isEqualTo(crewA.membershipId());
        assertThat(shift.get("active_request").isNull()).isTrue();
        assertThat(texts(shiftDetail(crewA.userId(), shiftId).get("allowed_actions")))
                .contains("request_substitute");
    }

    // ---- 6. 크루원 A 탈퇴 ----

    @Test
    void A가_탈퇴하면_과거_근무와_요청_기록이_탈퇴한_구성원으로_보인다() throws Exception {
        long laterShift = registerShift(crewA.membershipId(), 2);
        long requestId = requests.createRequest(crewA.userId(), laterShift,
                List.of(crewB.membershipId(), crewC.membershipId())).get("id").asLong();
        requests.transitionOk(crewB.userId(), requestId, "accept");
        requests.transitionOk(managerUser, requestId, "approve");

        // 내일 근무(shiftId)가 끝난 뒤로 이동: A에게 미종료 배정 근무가 없다
        Instant pastShiftEnd = Instant.parse(shiftDetail(managerUser, shiftId).get("ends_at").asString());
        clock.set(pastShiftEnd.plus(Duration.ofHours(1)));

        withdraw(crewA.userId()).andExpect(status().isNoContent());

        LocalDate date = shiftDate(shiftId);
        for (long viewer : List.of(managerUser, crewB.userId())) {
            JsonNode detail = shiftDetail(viewer, shiftId);
            assertThat(detail.get("assignee").get("membership_id").asLong()).isEqualTo(crewA.membershipId());
            assertThat(detail.get("assignee").get("display_name").asString()).isEqualTo(WITHDRAWN_NAME);
            assertThat(detail.get("assignee").get("personal_color").asString()).isEqualTo(WITHDRAWN_COLOR);

            JsonNode item = findShift(dayShifts(viewer, date).get("shifts"), shiftId);
            assertThat(item.get("assignee").get("display_name").asString()).isEqualTo(WITHDRAWN_NAME);

            JsonNode request = requests.data(requests.detail(viewer, requestId).andExpect(status().isOk()));
            assertThat(request.get("status").asString()).isEqualTo("APPROVED");
            assertThat(request.get("requester").get("display_name").asString()).isEqualTo(WITHDRAWN_NAME);
            assertThat(request.get("candidate").get("display_name").asString()).isEqualTo(crewB.name());
        }

        JsonNode received = requests.data(requests.list(crewB.userId(), "received", null)
                .andExpect(status().isOk()));
        JsonNode listed = null;
        for (JsonNode item : received.get("items")) {
            if (item.get("id").asLong() == requestId) {
                listed = item;
            }
        }
        assertThat(listed).as("B의 받은 요청 목록에 요청이 있어야 한다").isNotNull();
        assertThat(listed.get("requester").get("display_name").asString()).isEqualTo(WITHDRAWN_NAME);
    }

    // ---- 헬퍼 ----

    private Class<?> implOf(Class<?> type) {
        assertThat(context.getBeansOfType(type)).as(type.getSimpleName() + " 빈 개수").hasSize(1);
        return AopUtils.getTargetClass(context.getBean(type));
    }

    /** 소셜 로그인으로 가입하고 동의·이름·역할까지 마친다. */
    private long signUpBySocial(String name, String role) throws Exception {
        String token = new AuthApi(mockMvc, jsonMapper).login("sub-" + UUID.randomUUID())
                .get("access_token").asString();
        long userId = Long.parseLong(accessTokenService.decoder().decode(token).getSubject());
        onboard(userId, name, role);
        return userId;
    }

    private Crew joinCrew(String name, String code) throws Exception {
        long userId = onboarding.newUser();
        onboard(userId, name, "CREW");
        JsonNode joined = stores.data(stores.join(userId, code).andExpect(status().isOk()));
        return new Crew(name, userId, joined.get("membership_id").asLong());
    }

    private void onboard(long userId, String name, String role) throws Exception {
        onboarding.agree(userId, onboarding.allAgreements(userId, true)).andExpect(status().isOk());
        onboarding.setName(userId, name).andExpect(status().isOk());
        onboarding.selectRole(userId, role).andExpect(status().isOk());
    }

    /** 매니저가 일괄 등록 API(미리보기 → 등록)로 daysLater일 뒤 09:00~17:00(서울) 근무 1건을 만든다. */
    private long registerShift(long assigneeMembershipId, int daysLater) throws Exception {
        LocalDate day = LocalDate.ofInstant(clock.instant(), SEOUL).plusDays(daysLater);
        Instant start = day.atTime(LocalTime.of(9, 0)).atZone(SEOUL).toInstant();
        Map<String, Object> body = new HashMap<>();
        body.put("store_id", storeId);
        body.put("assignee_membership_ids", List.of(assigneeMembershipId));
        body.put("starts_at", start.toString());
        body.put("ends_at", start.plus(Duration.ofHours(8)).toString());

        JsonNode preview = data(mockMvc.perform(post("/shift-batches/preview")
                        .header(TestUserAuthenticationFilter.HEADER, managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        body.put("preview_token", preview.get("preview_token").asString());
        JsonNode created = data(mockMvc.perform(post("/shift-batches")
                        .header(TestUserAuthenticationFilter.HEADER, managerUser)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isOk()));
        assertThat(created.get("created_count").asInt()).isEqualTo(1);
        return created.get("shift_ids").get(0).asLong();
    }

    private ResultActions shiftDetailRaw(long userId, long id) throws Exception {
        return mockMvc.perform(get("/shifts/{id}", id).header(TestUserAuthenticationFilter.HEADER, userId));
    }

    private JsonNode shiftDetail(long userId, long id) throws Exception {
        return data(shiftDetailRaw(userId, id).andExpect(status().isOk()));
    }

    private LocalDate shiftDate(long id) throws Exception {
        return LocalDate.parse(shiftDetail(managerUser, id).get("local_date").asString());
    }

    private JsonNode dayShifts(long userId, LocalDate date) throws Exception {
        return data(mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                        .header(TestUserAuthenticationFilter.HEADER, userId)
                        .param("date", date.toString()))
                .andExpect(status().isOk()));
    }

    private JsonNode monthShifts(long userId, YearMonth month, String scope) throws Exception {
        return data(mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                        .header(TestUserAuthenticationFilter.HEADER, userId)
                        .param("month", month.toString())
                        .param("scope", scope))
                .andExpect(status().isOk()));
    }

    private ResultActions deleteShift(long userId, long id, long expectedVersion) throws Exception {
        return mockMvc.perform(delete("/shifts/{id}", id)
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .param("expected_version", String.valueOf(expectedVersion)));
    }

    private ResultActions leave(long userId) throws Exception {
        return mockMvc.perform(post("/stores/{storeId}/leave", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId));
    }

    private ResultActions withdraw(long userId) throws Exception {
        return mockMvc.perform(delete("/me").header(TestUserAuthenticationFilter.HEADER, userId));
    }

    private static JsonNode findShift(JsonNode shifts, long id) {
        for (JsonNode item : shifts) {
            if (item.get("shift_id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("근무 " + id + "가 목록에 없다: " + shifts);
    }

    private static JsonNode findDay(JsonNode days, LocalDate date) {
        for (JsonNode day : days) {
            if (day.get("date").asString().equals(date.toString())) {
                return day;
            }
        }
        throw new AssertionError(date + "가 days에 없다: " + days);
    }

    private static List<Long> shiftIds(JsonNode month) {
        return month.get("shifts").valueStream().map(n -> n.get("shift_id").asLong()).toList();
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
