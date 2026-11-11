package com.baton.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.account.api.AccountFixture.StoreInfo;
import com.baton.auth.application.AccessTokenService;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PRO-03 POST /stores/{store_id}/leave. */
@IntegrationTest
class StoreLeaveApiTest {

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
        store = fx.storeOf(manager, "나가기 카페");
    }

    @Test
    void 내일_근무가_남은_크루원은_나갈_수_없고_아무것도_바뀌지_않는다() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long membership = fx.join(crew, store.code());
        Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
        fx.shift(store.storeId(), membership, tomorrow, tomorrow.plus(Duration.ofHours(4)));

        fx.leave(crew, store.storeId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FUTURE_SHIFT_REMAINS"))
                .andExpect(jsonPath("$.current_state").doesNotExist());

        assertUnchanged(crew, membership);
    }

    @Test
    void 지금_진행_중인_근무가_있어도_나갈_수_없다() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long membership = fx.join(crew, store.code());
        Instant now = Instant.now();
        fx.shift(store.storeId(), membership, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));

        fx.leave(crew, store.storeId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FUTURE_SHIFT_REMAINS"));

        assertUnchanged(crew, membership);
    }

    @Test
    void 종료된_과거_근무와_삭제된_근무만_있으면_나가고_계정은_유지된다() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long membership = fx.join(crew, store.code());
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        fx.shift(store.storeId(), membership, yesterday, yesterday.plus(Duration.ofHours(4)));
        Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
        long deleted = fx.shift(store.storeId(), membership, tomorrow, tomorrow.plus(Duration.ofHours(4)));
        jdbcTemplate.update("UPDATE shifts SET deleted_at = now() WHERE id = ?", deleted);

        fx.leave(crew, store.storeId())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, user_id, left_at FROM memberships WHERE id = ?", membership);
        assertThat(row.get("state")).isEqualTo("LEFT");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(crew);
        assertThat(row.get("left_at")).isNotNull();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT onboarding_stage, selected_role, display_name, account_state FROM users WHERE id = ?", crew);
        assertThat(user.get("onboarding_stage")).isEqualTo("STORE_REQUIRED");
        assertThat(user.get("selected_role")).isEqualTo("CREW");
        assertThat(user.get("display_name")).isEqualTo("하윤");
        assertThat(user.get("account_state")).isEqualTo("ACTIVE");
    }

    @Test
    void 나간_크루원은_ST_03으로_가고_이전_매장_접근이_막히며_재참여하면_새_소속이_생긴다() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long oldMembership = fx.join(crew, store.code());

        fx.leave(crew, store.storeId()).andExpect(status().isNoContent());

        fx.onboarding.me(crew)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_screen").value("ST-03"))
                .andExpect(jsonPath("$.data.membership").isEmpty());
        fx.dayShifts(crew, store.storeId(), LocalDate.now(AccountFixture.SEOUL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        fx.stores.getStore(crew, store.storeId()).andExpect(status().isForbidden());
        fx.stores.members(crew, store.storeId(), null).andExpect(status().isForbidden());

        long newMembership = fx.join(crew, store.code());

        assertThat(newMembership).isNotEqualTo(oldMembership);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM memberships WHERE id = ?", String.class,
                oldMembership)).isEqualTo("LEFT");
        fx.onboarding.me(crew).andExpect(jsonPath("$.data.next_screen").value("CA-01"))
                .andExpect(jsonPath("$.data.membership.membership_id").value(newMembership));
    }

    @Test
    void 나간_매니저는_ST_01로_간다() throws Exception {
        fx.leave(manager, store.storeId()).andExpect(status().isNoContent());

        fx.onboarding.me(manager)
                .andExpect(jsonPath("$.data.next_screen").value("ST-01"))
                .andExpect(jsonPath("$.data.selected_role").value("MANAGER"));
        fx.dayShifts(manager, store.storeId(), LocalDate.now(AccountFixture.SEOUL))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장이거나_이미_나간_소속이면_403() throws Exception {
        long crew = fx.userNamed("하윤", "CREW");
        long membership = fx.join(crew, store.code());
        long otherManager = fx.userNamed("김매니저", "MANAGER");
        StoreInfo other = fx.storeOf(otherManager, "다른 카페");

        fx.leave(crew, other.storeId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertUnchanged(crew, membership);

        fx.leave(crew, store.storeId()).andExpect(status().isNoContent());
        fx.leave(crew, store.storeId()).andExpect(status().isForbidden());
    }

    private void assertUnchanged(long userId, long membershipId) throws Exception {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, user_id, left_at, version FROM memberships WHERE id = ?", membershipId);
        assertThat(row.get("state")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(row.get("left_at")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT onboarding_stage FROM users WHERE id = ?", String.class,
                userId)).isEqualTo("COMPLETED");
        JsonNode me = fx.onboarding.data(fx.onboarding.me(userId).andExpect(status().isOk()));
        assertThat(me.get("next_screen").asString()).isEqualTo("CA-01");
    }
}
