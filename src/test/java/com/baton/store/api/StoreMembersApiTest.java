package com.baton.store.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class StoreMembersApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;

    OnboardingApi onboarding;
    StoreApi api;

    long managerId;
    long storeId;
    long crewHa;      // 하윤
    long crewGa1;     // 가은 (먼저 참여)
    long crewNa;      // 나래
    long crewGa2;     // 가은 (동명이인, 나중 참여)
    long managerMembershipId;

    @BeforeEach
    void setUp() throws Exception {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);

        managerId = userNamed("최매니저", "MANAGER");
        JsonNode store = api.createStore(managerId, "바톤 카페");
        storeId = store.get("store_id").asLong();
        managerMembershipId = store.get("membership_id").asLong();
        String code = store.get("invite_code").asString();

        crewHa = joined(userNamed("하윤", "CREW"), code);
        crewGa1 = joined(userNamed("가은", "CREW"), code);
        crewNa = joined(userNamed("나래", "CREW"), code);
        crewGa2 = joined(userNamed("가은", "CREW"), code);

        // 다른 매장 구성원은 섞이지 않는다
        String otherCode = api.createStore(userNamed("다른매니저", "MANAGER"), "다른 매장").get("invite_code").asString();
        joined(userNamed("다른크루", "CREW"), otherCode);
    }

    @Test
    void all은_매니저_먼저_한국어_이름순_같으면_membership_id순() throws Exception {
        JsonNode data = api.data(api.members(crewNa, storeId, null).andExpect(status().isOk()));

        assertThat(names(data)).containsExactly("최매니저", "가은", "가은", "나래", "하윤");
        assertThat(data.get(0).get("role").asString()).isEqualTo("MANAGER");
        assertThat(data.get(0).get("membership_id").asLong()).isEqualTo(managerMembershipId);
        assertThat(data.get(1).get("membership_id").asLong()).isLessThan(data.get(2).get("membership_id").asLong());

        // is_me는 호출자 본인만
        List<JsonNode> me = new ArrayList<>();
        data.forEach(m -> {
            if (m.get("is_me").asBoolean()) {
                me.add(m);
            }
        });
        assertThat(me).singleElement().satisfies(m -> assertThat(m.get("display_name").asString()).isEqualTo("나래"));

        // 항목 필드: 이메일·소셜 정보 없음
        JsonNode first = data.get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder(
                "membership_id", "display_name", "role", "personal_color", "is_me");
        assertThat(first.get("personal_color").asString()).matches("^#[0-9A-F]{6}$");

        api.members(crewNa, storeId, "all").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
        api.getStore(crewNa, storeId).andExpect(jsonPath("$.data.member_count").value(5));
    }

    @Test
    void substitute_candidates는_본인과_매니저_제외_크루만() throws Exception {
        JsonNode data = api.data(api.members(crewNa, storeId, "substitute_candidates").andExpect(status().isOk()));

        assertThat(names(data)).containsExactly("가은", "가은", "하윤");
        data.forEach(m -> {
            assertThat(m.get("role").asString()).isEqualTo("CREW");
            assertThat(m.get("is_me").asBoolean()).isFalse();
        });
    }

    @Test
    void 매니저가_조회한_substitute_candidates는_크루_전원() throws Exception {
        JsonNode data = api.data(api.members(managerId, storeId, "substitute_candidates")
                .andExpect(status().isOk()));
        assertThat(names(data)).containsExactly("가은", "가은", "나래", "하윤");
    }

    @Test
    void 잘못된_purpose는_422() throws Exception {
        api.members(crewNa, storeId, "everyone")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("purpose"));
    }

    private long userNamed(String name, String role) throws Exception {
        long userId = onboarding.newUser();
        onboarding.agree(userId, onboarding.allAgreements(userId, true)).andExpect(status().isOk());
        onboarding.setName(userId, name).andExpect(status().isOk());
        onboarding.selectRole(userId, role).andExpect(status().isOk());
        return userId;
    }

    private long joined(long userId, String code) throws Exception {
        api.join(userId, code).andExpect(status().isOk());
        return userId;
    }

    private static List<String> names(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.forEach(m -> names.add(m.get("display_name").asString()));
        return names;
    }
}
