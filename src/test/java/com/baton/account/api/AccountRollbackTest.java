package com.baton.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.account.api.AccountFixture.LoggedIn;
import com.baton.account.api.AccountFixture.StoreInfo;
import com.baton.auth.application.AccessTokenService;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** 요청 정리(B)가 실패하면 소속 해제·파기도 함께 롤백된다(PRO-03 "소속 해제와 요청 정리는 함께 확정"). */
@IntegrationTest
class AccountRollbackTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AccessTokenService accessTokenService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean SubstituteRequestLifecycle substituteRequestLifecycle;

    AccountFixture fx;
    StoreInfo store;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AccountFixture(mockMvc, jsonMapper, userRepository, membershipRepository, shiftRepository,
                accessTokenService);
        long manager = fx.userNamed("최매니저", "MANAGER");
        store = fx.storeOf(manager, "롤백 카페");
        willThrow(new IllegalStateException("B 처리 실패"))
                .given(substituteRequestLifecycle).cleanupOnMembershipLeave(anyLong());
    }

    @Test
    void 요청_정리에서_예외가_나면_나가기가_롤백된다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());

        fx.leave(crew.userId(), store.storeId())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertUntouched(crew, membership);
    }

    @Test
    void 요청_정리에서_예외가_나면_탈퇴가_롤백된다() throws Exception {
        LoggedIn crew = fx.loginAndOnboard("하윤", "CREW");
        long membership = fx.join(crew.userId(), store.code());

        fx.withdraw(crew.userId())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertUntouched(crew, membership);
        verify(substituteRequestLifecycle, never()).anonymizeFor(anyLong());
    }

    private void assertUntouched(LoggedIn crew, long membership) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT state, user_id, left_at FROM memberships WHERE id = ?", membership);
        assertThat(row.get("state")).isEqualTo("ACTIVE");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(crew.userId());
        assertThat(row.get("left_at")).isNull();
        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT onboarding_stage, account_state, display_name FROM users WHERE id = ?", crew.userId());
        assertThat(user.get("onboarding_stage")).isEqualTo("COMPLETED");
        assertThat(user.get("account_state")).isEqualTo("ACTIVE");
        assertThat(user.get("display_name")).isEqualTo("하윤");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM social_accounts WHERE user_id = ?",
                Long.class, crew.userId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id = ?",
                Long.class, crew.userId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_consents WHERE user_id = ?",
                Long.class, crew.userId())).isPositive();
    }
}
