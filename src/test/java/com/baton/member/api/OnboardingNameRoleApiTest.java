package com.baton.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.Matchers;
import com.baton.support.OnboardingApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OnboardingNameRoleApiTest {

    private static final String EMOJI = "😀";

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    OnboardingApi api;

    @BeforeEach
    void setUp() {
        api = new OnboardingApi(mockMvc, jsonMapper, userRepository);
    }

    @Test
    void 이름은_앞뒤_공백을_제거해_저장한다() throws Exception {
        long userId = nameRequiredUser();

        api.setName(userId, " 김도연 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("김도연"));

        assertThat(jdbcTemplate.queryForObject("select display_name from users where id = ?", String.class, userId))
                .isEqualTo("김도연");
    }

    @Test
    void 이모지_포함_15자는_통과하고_16자는_거부() throws Exception {
        String fifteen = "김도연" + EMOJI.repeat(12);

        long rejected = nameRequiredUser();
        api.setName(rejected, fifteen + EMOJI).andExpect(invalidName());

        long accepted = nameRequiredUser();
        api.setName(accepted, fifteen).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value(fifteen));
        assertThat(jdbcTemplate.queryForObject("select display_name from users where id = ?", String.class, accepted))
                .isEqualTo(fifteen);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"김", "가가가가가가가가가가가가가가가가", "   ", "", "김\n도연", "김도연\n", "김\t도연"})
    void 유효하지_않은_이름은_422이고_단계를_유지한다(String name) throws Exception {
        long userId = nameRequiredUser();

        api.setName(userId, name).andExpect(invalidName());

        api.me(userId).andExpect(jsonPath("$.data.onboarding_stage").value("NAME_REQUIRED"))
                .andExpect(jsonPath("$.data.display_name").isEmpty());
    }

    @Test
    void 이름_저장만으로_매장_소속이_생기지_않는다() throws Exception {
        long userId = nameRequiredUser();
        api.setName(userId, "김도연").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership").isEmpty());

        assertThat(membershipCount(userId)).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ADMIN", "manager", "crew", "", " MANAGER"})
    void MANAGER_CREW_외_역할은_422(String role) throws Exception {
        long userId = roleRequiredUser();

        api.selectRole(userId, role)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("role"));

        api.me(userId).andExpect(jsonPath("$.data.onboarding_stage").value("ROLE_REQUIRED"))
                .andExpect(jsonPath("$.data.selected_role").isEmpty());
    }

    @Test
    void 역할_저장만으로_매장_소속이나_권한이_생기지_않는다() throws Exception {
        long userId = roleRequiredUser();

        api.selectRole(userId, "MANAGER").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership").isEmpty());

        assertThat(membershipCount(userId)).isZero();
    }

    private long nameRequiredUser() throws Exception {
        long userId = api.newUser();
        api.agree(userId, api.allAgreements(userId, false)).andExpect(status().isOk());
        return userId;
    }

    private long roleRequiredUser() throws Exception {
        long userId = nameRequiredUser();
        api.setName(userId, "김도연").andExpect(status().isOk());
        return userId;
    }

    private long membershipCount(long userId) {
        return jdbcTemplate.queryForObject("select count(*) from memberships where user_id = ?", Long.class, userId);
    }

    private static ResultMatcher invalidName() {
        return Matchers.all(
                status().isUnprocessableContent(),
                jsonPath("$.error.code").value("VALIDATION_FAILED"),
                jsonPath("$.error.field_errors[0].field").value("display_name"));
    }
}
