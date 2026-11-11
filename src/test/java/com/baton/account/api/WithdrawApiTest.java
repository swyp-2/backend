package com.baton.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.account.api.AccountFixture.LoggedIn;
import com.baton.account.api.AccountFixture.StoreInfo;
import com.baton.auth.application.AccessTokenService;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.application.MembershipGuard;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PRO-03 DELETE /me (계정 탈퇴, 정책 D05·8절). */
@IntegrationTest
class WithdrawApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AccessTokenService accessTokenService;
    @Autowired JdbcTemplate jdbcTemplate;

    AccountFixture fx;
    long manager;
    StoreInfo store;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AccountFixture(mockMvc, jsonMapper, userRepository, membershipRepository, shiftRepository,
                accessTokenService);
        manager = fx.userNamed("최매니저", "MANAGER");
        store = fx.storeOf(manager, "탈퇴 카페");
    }

    @Test
    void 내일_근무가_남은_크루원은_탈퇴할_수_없고_아무것도_바뀌지_않는다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());
        Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
        fx.shift(store.storeId(), membership, tomorrow, tomorrow.plus(Duration.ofHours(4)));

        fx.withdraw(crew.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FUTURE_SHIFT_REMAINS"))
                .andExpect(jsonPath("$.current_state").doesNotExist());

        assertNotWithdrawn(crew, membership);
    }

    @Test
    void 지금_진행_중인_근무가_있어도_탈퇴할_수_없다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());
        Instant now = Instant.now();
        fx.shift(store.storeId(), membership, now.minus(Duration.ofHours(2)), now.plus(Duration.ofMinutes(30)));

        fx.withdraw(crew.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FUTURE_SHIFT_REMAINS"));

        assertNotWithdrawn(crew, membership);
    }

    @Test
    void 과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        fx.shift(store.storeId(), membership, yesterday, yesterday.plus(Duration.ofHours(4)));
        // 멱등 기록(응답 본문에 개인정보가 있을 수 있음)
        jdbcTemplate.update("""
                INSERT INTO idempotency_records
                    (user_id, scope, idempotency_key, request_fingerprint, created_at, expires_at)
                VALUES (?, 'STORE_CREATE', 'k1', repeat('a', 64), now(), now() + interval '1 day')
                """, crew.userId());

        fx.withdraw(crew.userId())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        long userId = crew.userId();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT display_name, selected_role, account_state, withdrawn_at FROM users WHERE id = ?", userId);
        assertThat(user.get("display_name")).isNull();
        assertThat(user.get("selected_role")).isNull();
        assertThat(user.get("account_state")).isEqualTo("WITHDRAWN");
        assertThat(user.get("withdrawn_at")).isNotNull();
        assertThat(count("SELECT count(*) FROM social_accounts WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM social_accounts WHERE provider_subject = ?", crew.subject()))
                .isZero();
        assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM memberships WHERE user_id = ?", userId)).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE user_id = ?", userId)).isZero();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, user_id, left_at FROM memberships WHERE id = ?", membership);
        assertThat(row.get("state")).isEqualTo("WITHDRAWN");
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("left_at")).isNotNull();
        // 근무 이벤트에도 이름이 없다
        assertThat(count("SELECT count(*) FROM shift_events WHERE before_value::text LIKE '%하윤%' "
                + "OR after_value::text LIKE '%하윤%'")).isZero();
    }

    @Test
    void 탈퇴_직후_기존_액세스_토큰은_401이다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        mockMvc.perform(get("/me").header("Authorization", AuthApi.bearer(crew.accessToken())))
                .andExpect(status().isOk());

        fx.withdraw(crew.userId()).andExpect(status().isNoContent());

        mockMvc.perform(get("/me").header("Authorization", AuthApi.bearer(crew.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        fx.onboarding.me(crew.userId()).andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_구성원이_보는_과거_근무_담당자는_탈퇴한_구성원으로_표시된다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());
        LocalDate yesterday = LocalDate.now(AccountFixture.SEOUL).minusDays(1);
        Instant start = yesterday.atTime(9, 0).atZone(AccountFixture.SEOUL).toInstant();
        long shiftId = fx.shift(store.storeId(), membership, start, start.plus(Duration.ofHours(4)));

        fx.withdraw(crew.userId()).andExpect(status().isNoContent());

        JsonNode day = fx.onboarding.data(fx.dayShifts(manager, store.storeId(), yesterday)
                .andExpect(status().isOk()));
        JsonNode item = findShift(day.get("shifts"), shiftId);
        assertThat(item.get("assignee").get("display_name").asString())
                .isEqualTo(MembershipGuard.WITHDRAWN_MEMBER_NAME);
        assertThat(item.get("assignee").get("membership_id").asLong()).isEqualTo(membership);
        // 탈퇴한 구성원은 구성원 목록에서 빠진다
        JsonNode members = fx.onboarding.data(fx.stores.members(manager, store.storeId(), null)
                .andExpect(status().isOk()));
        assertThat(members.toString()).doesNotContain("하윤");
    }

    @Test
    void 같은_소셜_계정으로_다시_로그인하면_새_회원이다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        fx.withdraw(crew.userId()).andExpect(status().isNoContent());

        JsonNode again = fx.auth.login(crew.subject());

        long newUser = Long.parseLong(
                accessTokenService.decoder().decode(again.get("access_token").asString()).getSubject());
        assertThat(newUser).isNotEqualTo(crew.userId());
        assertThat(again.get("onboarding_stage").asString()).isEqualTo("CONSENT_REQUIRED");
        mockMvc.perform(get("/me").header("Authorization", AuthApi.bearer(again.get("access_token").asString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_screen").value("CO-02"))
                .andExpect(jsonPath("$.data.display_name").isEmpty());
    }

    @Test
    void 나갔다가_다른_매장에_참여한_뒤_탈퇴하면_모든_소속의_연결이_끊긴다() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long first = fx.join(crew, store.code());
        fx.leave(crew, store.storeId()).andExpect(status().isNoContent());
        long otherManager = fx.userNamed("김매니저", "MANAGER");
        StoreInfo other = fx.storeOf(otherManager, "두번째 카페");
        long second = fx.join(crew, other.code());

        fx.withdraw(crew).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, state, user_id FROM memberships WHERE id IN (?, ?) ORDER BY id", first, second);
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("state")).isEqualTo("WITHDRAWN");
            assertThat(row.get("user_id")).isNull();
        });
    }

    @Test
    void 매장_없이_온보딩_중인_회원도_탈퇴할_수_있다() throws Exception {
        long user = fx.onboarding.newUser();

        fx.withdraw(user).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT account_state FROM users WHERE id = ?", String.class, user))
                .isEqualTo("WITHDRAWN");
        fx.withdraw(user).andExpect(status().isUnauthorized());
    }

    private void assertNotWithdrawn(LoggedIn crew, long membership) throws Exception {
        long userId = crew.userId();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT display_name, selected_role, account_state, withdrawn_at, onboarding_stage FROM users "
                        + "WHERE id = ?", userId);
        assertThat(user.get("display_name")).isEqualTo("하윤");
        assertThat(user.get("selected_role")).isEqualTo("CREW");
        assertThat(user.get("account_state")).isEqualTo("ACTIVE");
        assertThat(user.get("withdrawn_at")).isNull();
        assertThat(user.get("onboarding_stage")).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM social_accounts WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", userId)).isPositive();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = ?", userId)).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, user_id FROM memberships WHERE id = ?", membership);
        assertThat(row.get("state")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
        mockMvc.perform(get("/me").header("Authorization", AuthApi.bearer(crew.accessToken())))
                .andExpect(status().isOk());
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private static JsonNode findShift(JsonNode shifts, long shiftId) {
        for (JsonNode shift : shifts) {
            if (shift.get("shift_id").asLong() == shiftId) {
                return shift;
            }
        }
        throw new AssertionError("근무 없음: " + shiftId);
    }
}
