package com.baton.store.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.store.domain.InviteCodeGenerator;
import com.baton.store.domain.StoreRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 초대 코드 유니크 충돌 시 같은 트랜잭션 안에서 새 코드로 재시도(최대 5회). */
@IntegrationTest
class InviteCodeIssueRetryTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @MockitoSpyBean InviteCodeGenerator generator;

    OnboardingApi onboarding;
    StoreApi api;
    String takenCode;

    @BeforeEach
    void setUp() throws Exception {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
        takenCode = api.createStore(onboarding.userWithRole("MANAGER"), "기존 매장").get("invite_code").asString();
    }

    @AfterEach
    void tearDown() {
        reset(generator);
    }

    @Test
    void 이미_있는_코드가_나오면_새_코드로_재시도() throws Exception {
        doReturn(takenCode).doReturn(takenCode).doCallRealMethod().when(generator).generate();

        JsonNode created = api.createStore(onboarding.userWithRole("MANAGER"), "새 매장");

        assertThat(created.get("invite_code").asString()).matches("^[A-Z0-9]{8}$").isNotEqualTo(takenCode);
    }

    @Test
    void 다섯_번_모두_충돌하면_500이고_아무것도_남지_않는다() throws Exception {
        doReturn(takenCode).when(generator).generate();
        long managerId = onboarding.userWithRole("MANAGER");
        long before = storeRepository.count();

        api.create(managerId, "새 매장", UUID.randomUUID().toString())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(storeRepository.count()).isEqualTo(before);
        onboarding.me(managerId).andExpect(jsonPath("$.data.onboarding_stage").value("STORE_REQUIRED"))
                .andExpect(jsonPath("$.data.membership").isEmpty());
    }
}
