package com.baton.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.Role;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import com.baton.support.Matchers;
import com.baton.support.OnboardingApi;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class OnboardingFlowApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired Clock clock;

    OnboardingApi api;

    @BeforeEach
    void setUp() {
        api = new OnboardingApi(mockMvc, jsonMapper, userRepository);
    }

    @Test
    void 신규_회원_매니저_흐름_CO02_CO04_CO05_ST01() throws Exception {
        long userId = api.newUser();

        api.me(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value(userId))
                .andExpect(jsonPath("$.data.display_name").isEmpty())
                .andExpect(jsonPath("$.data.onboarding_stage").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.data.selected_role").isEmpty())
                .andExpect(jsonPath("$.data.membership").isEmpty())
                .andExpect(jsonPath("$.data.next_screen").value("CO-02"))
                .andExpect(jsonPath("$.meta.server_time").exists());

        api.agree(userId, api.allAgreements(userId, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_stage").value("NAME_REQUIRED"))
                .andExpect(jsonPath("$.data.next_screen").value("CO-04"));
        api.me(userId).andExpect(jsonPath("$.data.next_screen").value("CO-04"));

        api.setName(userId, " 김도연 ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("김도연"))
                .andExpect(jsonPath("$.data.onboarding_stage").value("ROLE_REQUIRED"))
                .andExpect(jsonPath("$.data.next_screen").value("CO-05"));
        api.me(userId).andExpect(jsonPath("$.data.next_screen").value("CO-05"));

        api.selectRole(userId, "MANAGER").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selected_role").value("MANAGER"))
                .andExpect(jsonPath("$.data.onboarding_stage").value("STORE_REQUIRED"))
                .andExpect(jsonPath("$.data.membership").isEmpty())
                .andExpect(jsonPath("$.data.next_screen").value("ST-01"));
        api.me(userId).andExpect(jsonPath("$.data.next_screen").value("ST-01"))
                .andExpect(jsonPath("$.data.display_name").value("김도연"));
    }

    @Test
    void 신규_회원_크루원_흐름은_ST03() throws Exception {
        long userId = api.userWithRole("CREW");

        api.me(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selected_role").value("CREW"))
                .andExpect(jsonPath("$.data.next_screen").value("ST-03"));
    }

    @Test
    void 정책10절_이름_역할까지만_저장한_계정은_ST01_ST03으로_복귀하고_매장_데이터는_403() throws Exception {
        Store otherStore = storeRepository.saveAndFlush(Store.open("남의매장", clock.instant()));
        for (String role : new String[] {"MANAGER", "CREW"}) {
            long userId = api.userWithRole(role);

            // 앱 종료 후 재진입: 동의·이름을 다시 묻지 않는다
            api.me(userId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.onboarding_stage").value("STORE_REQUIRED"))
                    .andExpect(jsonPath("$.data.next_screen").value(role.equals("MANAGER") ? "ST-01" : "ST-03"))
                    .andExpect(jsonPath("$.data.membership").isEmpty());

            // 매장 없이 매장 데이터 접근 불가
            api.getStore(userId, otherStore.getId()).andExpect(forbidden());
        }
    }

    @Test
    @Disabled("근무 조회 API(GET /stores/{store_id}/shifts) 구현 후 활성화. 현재는 컨트롤러가 없어 404")
    void 정책10절_이름_역할까지만_저장한_계정의_근무표_조회는_403() throws Exception {
        Store otherStore = storeRepository.saveAndFlush(Store.open("남의매장", clock.instant()));
        long userId = api.userWithRole("CREW");

        mockMvc.perform(get("/stores/{storeId}/shifts", otherStore.getId())
                        .param("month", "2026-11")
                        .header(TestUserAuthenticationFilter.HEADER, userId))
                .andExpect(forbidden());
    }

    @Test
    void 역할_저장만으로는_어떤_매장에도_접근할_수_없다() throws Exception {
        long managerId = api.userWithRole("MANAGER");
        Store existing = storeRepository.saveAndFlush(Store.open("기존매장", clock.instant()));

        api.getStore(managerId, existing.getId()).andExpect(forbidden());
        api.getStore(managerId, 999_999_999L).andExpect(forbidden()); // 없는 매장도 403(존재 여부 비노출)
        assertThat(jdbcTemplate.queryForObject("select count(*) from memberships where user_id = ?",
                Long.class, managerId)).isZero();
    }

    @Test
    void 활성_소속이_있으면_CA01이고_소속_정보를_돌려준다() throws Exception {
        long userId = api.userWithRole("CREW");
        Store store = storeRepository.saveAndFlush(Store.open("성수점", clock.instant()));
        Membership membership = membershipRepository.saveAndFlush(
                Membership.join(store.getId(), userId, Role.CREW, "#AABBCC", clock.instant()));

        api.me(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.next_screen").value("CA-01"))
                .andExpect(jsonPath("$.data.membership.membership_id").value(membership.getId()))
                .andExpect(jsonPath("$.data.membership.store_id").value(store.getId()))
                .andExpect(jsonPath("$.data.membership.store_name").value("성수점"))
                .andExpect(jsonPath("$.data.membership.role").value("CREW"))
                .andExpect(jsonPath("$.data.membership.personal_color").value("#AABBCC"));
        api.getStore(userId, store.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store_id").value(store.getId()))
                .andExpect(jsonPath("$.data.name").value("성수점"));
    }

    @Test
    void 매장을_나간_계정은_저장된_역할_기준으로_복귀한다() throws Exception {
        long userId = api.userWithRole("MANAGER");
        Store store = storeRepository.saveAndFlush(Store.open("나간매장", clock.instant()));
        Membership membership = membershipRepository.saveAndFlush(
                Membership.join(store.getId(), userId, Role.MANAGER, "#AABBCC", clock.instant()));
        membership.leave(clock.instant());
        membershipRepository.saveAndFlush(membership);

        api.me(userId).andExpect(jsonPath("$.data.next_screen").value("ST-01"))
                .andExpect(jsonPath("$.data.membership").isEmpty());
        api.getStore(userId, store.getId()).andExpect(forbidden());
    }

    // ---- 단계 순서 위반: 각각 409 + current_state ----

    @Test
    void 동의_전_이름_저장은_409() throws Exception {
        long userId = api.newUser();
        api.setName(userId, "김도연").andExpect(stageConflict("CONSENT_REQUIRED"));
    }

    @Test
    void 동의_전_역할_저장은_409() throws Exception {
        long userId = api.newUser();
        api.selectRole(userId, "CREW").andExpect(stageConflict("CONSENT_REQUIRED"));
    }

    @Test
    void 이름_전_역할_저장은_409() throws Exception {
        long userId = api.newUser();
        api.agree(userId, api.allAgreements(userId, true)).andExpect(status().isOk());

        api.selectRole(userId, "CREW").andExpect(stageConflict("NAME_REQUIRED"));
        api.me(userId).andExpect(jsonPath("$.data.selected_role").isEmpty());
    }

    @Test
    void 이름_재저장은_409이고_기존_이름을_덮어쓰지_않는다() throws Exception {
        long userId = api.newUser();
        api.agree(userId, api.allAgreements(userId, true)).andExpect(status().isOk());
        api.setName(userId, "김도연").andExpect(status().isOk());

        api.setName(userId, "박소율").andExpect(stageConflict("ROLE_REQUIRED"));
        api.me(userId).andExpect(jsonPath("$.data.display_name").value("김도연"));
    }

    @Test
    void 매장_연결_단계에서_역할_재선택은_409() throws Exception {
        long userId = api.userWithRole("CREW");

        api.selectRole(userId, "MANAGER").andExpect(stageConflict("STORE_REQUIRED"));
        api.me(userId).andExpect(jsonPath("$.data.selected_role").value("CREW"));
    }

    @Test
    void 활성_소속이_있는_사용자의_역할_요청은_409() throws Exception {
        long userId = api.userWithRole("CREW");
        Store store = storeRepository.saveAndFlush(Store.open("소속매장", clock.instant()));
        membershipRepository.saveAndFlush(Membership.join(store.getId(), userId, Role.CREW, "#AABBCC",
                clock.instant()));

        api.selectRole(userId, "MANAGER").andExpect(stageConflict("STORE_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject("select role from memberships where user_id = ?", String.class,
                userId)).isEqualTo("CREW");
    }

    // ---- 개인정보: 응답 어디에도 이메일·소셜 식별자·토큰이 없다 ----

    @Test
    void 온보딩_응답에는_이메일_소셜_식별자_토큰이_없다() throws Exception {
        // 실제 소셜 로그인으로 만든 계정(social_accounts 연결 있음)으로 확인
        AuthApi authApi = new AuthApi(mockMvc, jsonMapper);
        String subject = "onboarding-privacy-" + UUID.randomUUID();
        authApi.login(subject);
        long userId = jdbcTemplate.queryForObject(
                "select user_id from social_accounts where provider_subject = ?", Long.class, subject);

        assertNoPrivateFields(api.me(userId));
        assertNoPrivateFields(api.documents(userId));
        assertNoPrivateFields(api.agree(userId, api.allAgreements(userId, true)));
        assertNoPrivateFields(api.setName(userId, "김도연"));
        assertNoPrivateFields(api.selectRole(userId, "CREW"));
        assertNoPrivateFields(api.selectRole(userId, "CREW")); // 에러 응답도
    }

    private static void assertNoPrivateFields(ResultActions result) throws Exception {
        for (String field : new String[] {"email", "provider", "provider_subject", "social_account",
                "social_accounts", "access_token", "id_token", "refresh_token", "session_id"}) {
            result.andExpect(jsonPath("$.." + field).doesNotExist());
        }
        String body = result.andReturn().getResponse().getContentAsString();
        // 소셜 식별자(subject) 원문이 어디에도 노출되지 않는다
        assertThat(body).doesNotContain("onboarding-privacy-");
    }

    private static ResultMatcher forbidden() {
        return Matchers.all(
                status().isForbidden(),
                jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static ResultMatcher stageConflict(String currentStage) {
        return Matchers.all(
                status().isConflict(),
                jsonPath("$.error.code").value("ONBOARDING_STAGE_CONFLICT"),
                jsonPath("$.current_state").value(currentStage));
    }
}
