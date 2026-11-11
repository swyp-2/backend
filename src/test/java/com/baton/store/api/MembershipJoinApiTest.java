package com.baton.store.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MembershipJoinApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    OnboardingApi onboarding;
    StoreApi api;
    long storeId;
    String code;

    @BeforeEach
    void setUp() throws Exception {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
        JsonNode store = api.createStore(onboarding.userWithRole("MANAGER"), "바톤 카페");
        storeId = store.get("store_id").asLong();
        code = store.get("invite_code").asString();
    }

    @Test
    void 크루원_가입_코드_참여_후_next_screen은_CA01() throws Exception {
        long crewId = onboarding.userWithRole("CREW");

        api.join(crewId, "  " + code + " ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store_id").value(storeId))
                .andExpect(jsonPath("$.data.store_name").value("바톤 카페"))
                .andExpect(jsonPath("$.data.membership_id").isNumber())
                .andExpect(jsonPath("$.data.role").value("CREW"));

        onboarding.me(crewId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_stage").value("COMPLETED"))
                .andExpect(jsonPath("$.data.membership.store_id").value(storeId))
                .andExpect(jsonPath("$.data.membership.role").value("CREW"))
                // 매니저가 첫 색을 쓰므로 두 번째 색
                .andExpect(jsonPath("$.data.membership.personal_color").value("#F76B15"))
                .andExpect(jsonPath("$.data.next_screen").value("CA-01"));
    }

    @Test
    void 형식_오류는_422_VALIDATION_FAILED_소문자도_거부() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        for (String input : new String[]{"abc12345", code.toLowerCase(), "", "ABC1234", "ABC123456", "ABCD-123", null}) {
            api.join(crewId, input)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field_errors[0].field").value("code"));
        }
    }

    @Test
    void 빈_값과_형식_오류는_같은_필드에서_다른_문구로_구분한다() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        api.join(crewId, "   ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("code"))
                .andExpect(jsonPath("$.error.field_errors[0].reason").value("매장 코드를 입력해 주세요."));
        api.join(crewId, "abc12345").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("code"))
                .andExpect(jsonPath("$.error.field_errors[0].reason").value("영문 대문자·숫자 8자리로 입력해 주세요."));
    }

    @Test
    void 초대_코드는_발급_후_시간이_지나도_매장이_있는_동안_유효하다() throws Exception {
        jdbcTemplate.update("UPDATE invite_codes SET created_at = now() - interval '3 years' WHERE code = ?", code);

        api.join(onboarding.userWithRole("CREW"), code).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store_id").value(storeId));
    }

    @Test
    void 없는_코드는_422_INVALID_INVITE_CODE() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        String unknown = code.equals("ZZZZZZZZ") ? "YYYYYYYY" : "ZZZZZZZZ";
        api.join(crewId, unknown)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVITE_CODE"));
        onboarding.me(crewId).andExpect(jsonPath("$.data.onboarding_stage").value("STORE_REQUIRED"));
    }

    @Test
    void 무효화된_코드는_INVALID_INVITE_CODE() throws Exception {
        jdbcTemplate.update("UPDATE invite_codes SET invalidated_at = now() WHERE code = ?", code);
        api.join(onboarding.userWithRole("CREW"), code)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVITE_CODE"));
    }

    @Test
    void 같은_매장_재참여는_기존_소속을_200으로() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        long membershipId = api.data(api.join(crewId, code).andExpect(status().isOk()))
                .get("membership_id").asLong();

        api.join(crewId, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membership_id").value(membershipId))
                .andExpect(jsonPath("$.data.store_id").value(storeId))
                .andExpect(jsonPath("$.data.role").value("CREW"));
        assertThat(membershipRepository.findAll().stream()
                .filter(m -> Long.valueOf(crewId).equals(m.getUserId()) && m.getState() == MembershipState.ACTIVE))
                .hasSize(1);
    }

    @Test
    void 다른_매장_소속이면_409_ALREADY_IN_STORE() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        api.join(crewId, code).andExpect(status().isOk());
        String otherCode = api.createStore(onboarding.userWithRole("MANAGER"), "다른 매장")
                .get("invite_code").asString();

        api.join(crewId, otherCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_IN_STORE"));
    }

    @Test
    void 매니저_역할이나_역할_선택_전이면_403() throws Exception {
        api.join(onboarding.userWithRole("MANAGER"), code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        api.join(onboarding.newUser(), code).andExpect(status().isForbidden());
    }
}
