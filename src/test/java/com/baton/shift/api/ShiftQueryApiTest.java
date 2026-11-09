package com.baton.shift.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ShiftQueryApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Clock clock;
    @MockitoBean SubstituteRequestLifecycle substituteRequestLifecycle;

    OnboardingApi onboarding;
    StoreApi api;

    long managerUser;
    long storeId;
    long managerMembership;
    String code;

    @BeforeEach
    void setUp() throws Exception {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
        managerUser = userNamed("최매니저", "MANAGER");
        JsonNode store = api.createStore(managerUser, "바톤 카페");
        storeId = store.get("store_id").asLong();
        managerMembership = store.get("membership_id").asLong();
        code = store.get("invite_code").asString();
    }

    // ---- 월 조회 ----

    @Test
    void 월경계_야간_근무는_시작한_달에만_나온다() throws Exception {
        long crew = crewNamed("하윤");
        long shiftId = shift(membershipOf(crew), kst(2026, 11, 30, 22, 0), Duration.ofHours(8));

        JsonNode nov = data(month(crew, "2026-11", "mine").andExpect(status().isOk()));
        assertThat(nov.get("month").asString()).isEqualTo("2026-11");
        assertThat(nov.get("shifts")).hasSize(1);
        JsonNode item = nov.get("shifts").get(0);
        assertThat(item.get("shift_id").asLong()).isEqualTo(shiftId);
        assertThat(item.get("local_date").asString()).isEqualTo("2026-11-30");
        assertThat(item.get("starts_at").asString()).isEqualTo("2026-11-30T13:00:00Z");
        assertThat(item.get("ends_at").asString()).isEqualTo("2026-11-30T21:00:00Z");
        assertThat(nov.get("days")).hasSize(1);
        assertThat(nov.get("days").get(0).get("date").asString()).isEqualTo("2026-11-30");

        JsonNode novAll = data(month(crew, "2026-11", "all").andExpect(status().isOk()));
        assertThat(novAll.get("days").get(0).get("date").asString()).isEqualTo("2026-11-30");

        JsonNode dec = data(month(crew, "2026-12", "mine").andExpect(status().isOk()));
        assertThat(dec.get("shifts")).isEmpty();
        assertThat(dec.get("days")).isEmpty();
        assertThat(data(month(crew, "2026-12", "all")).get("days")).isEmpty();
        assertThat(data(day(crew, "2026-12-01")).get("shifts")).isEmpty();
        assertThat(data(day(crew, "2026-11-30")).get("shifts")).hasSize(1);
    }

    @Test
    void 월_경계_자정_정각_시작은_다음_달에_속한다() throws Exception {
        long crew = crewNamed("하윤");
        shift(membershipOf(crew), kst(2026, 12, 1, 0, 0), Duration.ofHours(4));

        assertThat(data(month(crew, "2026-11", "mine")).get("shifts")).isEmpty();
        assertThat(data(month(crew, "2026-12", "mine")).get("shifts")).hasSize(1);
    }

    @Test
    void mine은_월_전체_내_근무만_시작순이고_all은_매장_기준_인원에_목록은_빈_배열() throws Exception {
        long me = crewNamed("하윤");
        long other = crewNamed("나래");
        long myMembership = membershipOf(me);
        long otherMembership = membershipOf(other);
        long late = shift(myMembership, kst(2026, 11, 20, 9, 0), Duration.ofHours(4));
        long early = shift(myMembership, kst(2026, 11, 3, 9, 0), Duration.ofHours(4));
        long sameDay = shift(myMembership, kst(2026, 11, 3, 15, 0), Duration.ofHours(4));
        shift(otherMembership, kst(2026, 11, 3, 9, 0), Duration.ofHours(4));
        shift(otherMembership, kst(2026, 11, 25, 9, 0), Duration.ofHours(4));
        long deleted = shift(myMembership, kst(2026, 11, 10, 9, 0), Duration.ofHours(4));
        deleteShift(deleted);

        JsonNode mine = data(month(me, "2026-11", "mine").andExpect(status().isOk()));
        assertThat(ids(mine.get("shifts"))).containsExactly(early, sameDay, late);
        mine.get("shifts").forEach(s -> assertThat(s.get("assignee").get("is_me").asBoolean()).isTrue());
        assertThat(dayCounts(mine)).containsExactly("2026-11-03=1", "2026-11-20=1");

        JsonNode all = data(month(me, "2026-11", "all").andExpect(status().isOk()));
        assertThat(all.get("shifts")).isEmpty();
        assertThat(dayCounts(all)).containsExactly("2026-11-03=2", "2026-11-20=1", "2026-11-25=1");
    }

    // ---- 날짜 조회 ----

    @Test
    void 같은_사람_같은_날_2건은_인원_1명_건수_2건() throws Exception {
        long crew = crewNamed("하윤");
        long membership = membershipOf(crew);
        shift(membership, kst(2026, 11, 9, 9, 0), Duration.ofHours(3));
        shift(membership, kst(2026, 11, 9, 18, 0), Duration.ofHours(3));

        day(crew, "2026-11-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-11-09"))
                .andExpect(jsonPath("$.data.headcount").value(1))
                .andExpect(jsonPath("$.data.shift_count").value(2))
                .andExpect(jsonPath("$.data.shifts.length()").value(2));

        JsonNode month = data(month(crew, "2026-11", "all"));
        assertThat(dayCounts(month)).containsExactly("2026-11-09=1");
    }

    @Test
    void 동시각_근무는_한국어_이름순_같으면_membership_id순() throws Exception {
        long na = membershipOf(crewNamed("나연"));
        long ga1 = membershipOf(crewNamed("가연"));
        long da = membershipOf(crewNamed("다연"));
        long ga2 = membershipOf(crewNamed("가연"));
        Instant start = kst(2026, 11, 9, 9, 0);
        for (long m : List.of(da, ga2, na, ga1)) {
            shift(m, start, Duration.ofHours(4));
        }
        long earlier = shift(da, kst(2026, 11, 9, 7, 0), Duration.ofHours(1));

        JsonNode data = data(day(managerUser, "2026-11-09").andExpect(status().isOk()));
        JsonNode shifts = data.get("shifts");
        assertThat(shifts.get(0).get("shift_id").asLong()).isEqualTo(earlier);
        List<String> names = new ArrayList<>();
        List<Long> members = new ArrayList<>();
        for (int i = 1; i < shifts.size(); i++) {
            names.add(shifts.get(i).get("assignee").get("display_name").asString());
            members.add(shifts.get(i).get("assignee").get("membership_id").asLong());
        }
        assertThat(names).containsExactly("가연", "가연", "나연", "다연");
        assertThat(members.subList(0, 2)).containsExactly(Math.min(ga1, ga2), Math.max(ga1, ga2));
        assertThat(data.get("headcount").asLong()).isEqualTo(4);
        assertThat(data.get("shift_count").asLong()).isEqualTo(5);
    }

    @Test
    void 일곱_명_있는_날은_일곱_명_모두_반환() throws Exception {
        for (int i = 0; i < 7; i++) {
            shift(membershipOf(crewNamed("크루" + (char) ('가' + i))), kst(2026, 11, 9, 9 + i, 0),
                    Duration.ofHours(2));
        }

        day(managerUser, "2026-11-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headcount").value(7))
                .andExpect(jsonPath("$.data.shift_count").value(7))
                .andExpect(jsonPath("$.data.shifts.length()").value(7));
    }

    @Test
    void 날짜_조회는_scope를_무시하고_삭제된_근무를_뺀다() throws Exception {
        long crew = crewNamed("하윤");
        shift(managerMembership, kst(2026, 11, 9, 9, 0), Duration.ofHours(2));
        deleteShift(shift(membershipOf(crew), kst(2026, 11, 9, 9, 0), Duration.ofHours(2)));

        mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                        .header(TestUserAuthenticationFilter.HEADER, crew)
                        .param("date", "2026-11-09").param("scope", "mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shift_count").value(1))
                .andExpect(jsonPath("$.data.shifts[0].assignee.membership_id").value(managerMembership))
                .andExpect(jsonPath("$.data.shifts[0].assignee.is_me").value(false));
    }

    @Test
    void 탈퇴한_담당자는_탈퇴한_구성원_회색() throws Exception {
        long crew = crewNamed("하윤");
        long membership = membershipOf(crew);
        shift(membership, kst(2026, 11, 9, 9, 0), Duration.ofHours(2));
        jdbcTemplate.update("UPDATE memberships SET state = 'WITHDRAWN', left_at = now(), user_id = NULL WHERE id = ?",
                membership);

        JsonNode assignee = data(day(managerUser, "2026-11-09")).get("shifts").get(0).get("assignee");
        assertThat(assignee.propertyNames()).containsExactlyInAnyOrder(
                "membership_id", "display_name", "personal_color", "is_me");
        assertThat(assignee.get("display_name").asString()).isEqualTo("탈퇴한 구성원");
        assertThat(assignee.get("personal_color").asString()).isEqualTo("#B0B4BA");
    }

    @Test
    void 잘못된_파라미터는_422() throws Exception {
        long crew = crewNamed("하윤");
        expect422(request(crew).param("month", "2026-11").param("date", "2026-11-09").param("scope", "mine"), "month");
        expect422(request(crew), "month");
        expect422(request(crew).param("month", "2026-11"), "scope");
        expect422(request(crew).param("month", "2026-11").param("scope", "everyone"), "scope");
        expect422(request(crew).param("month", "2026-13").param("scope", "mine"), "month");
        expect422(request(crew).param("month", "2026-1").param("scope", "mine"), "month");
        expect422(request(crew).param("date", "2026-11-31"), "date");
        expect422(request(crew).param("date", "20261109"), "date");
    }

    @Test
    void 다른_매장_구성원은_목록을_볼_수_없다() throws Exception {
        long outsider = userNamed("다른매니저", "MANAGER");
        api.createStore(outsider, "다른 매장");

        month(outsider, "2026-11", "all").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        day(outsider, "2026-11-09").andExpect(status().isForbidden());
    }

    // ---- 상세 ----

    @Test
    void 크루원_내_미래_근무_상세() throws Exception {
        long crew = crewNamed("하윤");
        long membership = membershipOf(crew);
        long shiftId = shift(membership, clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));

        JsonNode data = data(detail(crew, shiftId).andExpect(status().isOk()));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("shift_id", "starts_at", "ends_at", "local_date",
                "assignee", "version", "is_mine", "is_started", "active_request", "allowed_actions");
        assertThat(data.get("assignee").get("display_name").asString()).isEqualTo("하윤");
        assertThat(data.get("assignee").get("is_me").asBoolean()).isTrue();
        assertThat(data.get("version").asLong()).isZero();
        assertThat(data.get("is_mine").asBoolean()).isTrue();
        assertThat(data.get("is_started").asBoolean()).isFalse();
        assertThat(data.get("active_request").isNull()).isTrue();
        assertThat(strings(data.get("allowed_actions"))).containsExactly("request_substitute");
    }

    @Test
    void 활성_요청이_있으면_요약과_행동이_반영된다() throws Exception {
        long crew = crewNamed("하윤");
        long membership = membershipOf(crew);
        long shiftId = shift(membership, clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));
        when(substituteRequestLifecycle.findActiveByShift(shiftId, membership))
                .thenReturn(Optional.of(new ActiveRequestSummary(77L, "OPEN", true, false)));
        when(substituteRequestLifecycle.findActiveByShift(shiftId, managerMembership))
                .thenReturn(Optional.of(new ActiveRequestSummary(77L, "PENDING_MANAGER", false, true)));

        detail(crew, shiftId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active_request.request_id").value(77))
                .andExpect(jsonPath("$.data.active_request.status").value("OPEN"))
                .andExpect(jsonPath("$.data.active_request.viewer_is_requester").value(true))
                .andExpect(jsonPath("$.data.active_request.pending_manager_approval").value(false))
                .andExpect(jsonPath("$.data.allowed_actions.length()").value(1))
                .andExpect(jsonPath("$.data.allowed_actions[0]").value("view_request"));

        JsonNode managerView = data(detail(managerUser, shiftId).andExpect(status().isOk()));
        assertThat(managerView.get("is_mine").asBoolean()).isFalse();
        assertThat(strings(managerView.get("allowed_actions"))).containsExactly("delete", "view_approval_request");
    }

    @Test
    void 크루원이_타인_근무를_보면_행동_없고_매니저는_과거_근무도_삭제_가능() throws Exception {
        long crew = crewNamed("하윤");
        long other = crewNamed("나래");
        long future = shift(membershipOf(other), clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));
        long past = shift(membershipOf(other), clock.instant().minus(Duration.ofDays(1)), Duration.ofHours(4));

        JsonNode othersView = data(detail(crew, future).andExpect(status().isOk()));
        assertThat(othersView.get("is_mine").asBoolean()).isFalse();
        assertThat(othersView.get("allowed_actions")).isEmpty();

        JsonNode managerPast = data(detail(managerUser, past).andExpect(status().isOk()));
        assertThat(managerPast.get("is_started").asBoolean()).isTrue();
        assertThat(strings(managerPast.get("allowed_actions"))).containsExactly("delete");
    }

    @Test
    void 매니저_본인_근무에도_대타_요청_행동은_없다() throws Exception {
        long shiftId = shift(managerMembership, clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));

        JsonNode data = data(detail(managerUser, shiftId).andExpect(status().isOk()));
        assertThat(data.get("is_mine").asBoolean()).isTrue();
        assertThat(strings(data.get("allowed_actions"))).containsExactly("delete");
    }

    @Test
    void 다른_매장_근무_상세는_403() throws Exception {
        long crew = crewNamed("하윤");
        long shiftId = shift(membershipOf(crew), clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));
        long outsider = userNamed("다른매니저", "MANAGER");
        api.createStore(outsider, "다른 매장");

        detail(outsider, shiftId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 삭제된_근무와_없는_근무_상세는_404() throws Exception {
        long crew = crewNamed("하윤");
        long shiftId = shift(membershipOf(crew), clock.instant().plus(Duration.ofDays(1)), Duration.ofHours(4));
        deleteShift(shiftId);

        detail(crew, shiftId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        detail(crew, Long.MAX_VALUE).andExpect(status().isNotFound());
    }

    // ---- helpers ----

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

    private long membershipOf(long userId) {
        return membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).orElseThrow().getId();
    }

    private long shift(long membershipId, Instant start, Duration length) {
        return shiftRepository.saveAndFlush(
                Shift.schedule(storeId, membershipId, start, start.plus(length), clock.instant())).getId();
    }

    private void deleteShift(long shiftId) {
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.delete(clock.instant());
        shiftRepository.saveAndFlush(shift);
    }

    private static Instant kst(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(SEOUL).toInstant();
    }

    private MockHttpServletRequestBuilder request(long userId) {
        return get("/stores/{storeId}/shifts", storeId).header(TestUserAuthenticationFilter.HEADER, userId);
    }

    private ResultActions month(long userId, String month, String scope) throws Exception {
        return mockMvc.perform(request(userId).param("month", month).param("scope", scope));
    }

    private ResultActions day(long userId, String date) throws Exception {
        return mockMvc.perform(request(userId).param("date", date));
    }

    private ResultActions detail(long userId, long shiftId) throws Exception {
        return mockMvc.perform(get("/shifts/{shiftId}", shiftId).header(TestUserAuthenticationFilter.HEADER, userId));
    }

    private void expect422(MockHttpServletRequestBuilder request, String field) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value(field));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return api.data(result);
    }

    private static List<Long> ids(JsonNode shifts) {
        List<Long> ids = new ArrayList<>();
        shifts.forEach(s -> ids.add(s.get("shift_id").asLong()));
        return ids;
    }

    private static List<String> dayCounts(JsonNode monthData) {
        List<String> result = new ArrayList<>();
        monthData.get("days").forEach(d -> result.add(d.get("date").asString() + "=" + d.get("headcount").asLong()));
        return result;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asString()));
        return result;
    }
}
