package com.baton.store.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.StoreRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class StoreApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;

    OnboardingApi onboarding;
    StoreApi api;

    @BeforeEach
    void setUp() {
        onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new StoreApi(mockMvc, jsonMapper);
    }

    @Test
    void 매니저_가입_매장_생성_코드_확인() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");

        JsonNode created = api.data(api.create(managerId, "  바톤 카페 ", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("바톤 카페"))
                .andExpect(jsonPath("$.data.store_id").isNumber())
                .andExpect(jsonPath("$.data.membership_id").isNumber())
                .andExpect(jsonPath("$.data.invite_code").value(org.hamcrest.Matchers.matchesPattern("^[A-Z0-9]{8}$")))
                .andExpect(jsonPath("$.meta.server_time").exists()));
        long storeId = created.get("store_id").asLong();

        api.inviteCode(managerId, storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(created.get("invite_code").asString()));
        api.getStore(managerId, storeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store_id").value(storeId))
                .andExpect(jsonPath("$.data.name").value("바톤 카페"))
                .andExpect(jsonPath("$.data.member_count").value(1))
                .andExpect(jsonPath("$.data.my_role").value("MANAGER"));
        onboarding.me(managerId).andExpect(jsonPath("$.data.onboarding_stage").value("COMPLETED"))
                .andExpect(jsonPath("$.data.next_screen").value("CA-01"))
                .andExpect(jsonPath("$.data.membership.role").value("MANAGER"))
                .andExpect(jsonPath("$.data.membership.personal_color").value("#E5484D"));
    }

    @Test
    void 매장명_검증_실패는_422_name() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        for (String name : new String[]{"   ", "가나다라마바사아자차카타파하가나", null}) {
            api.create(managerId, name, UUID.randomUUID().toString())
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field_errors[0].field").value("name"));
        }
        onboarding.me(managerId).andExpect(jsonPath("$.data.onboarding_stage").value("STORE_REQUIRED"));
    }

    @Test
    void Idempotency_Key가_없으면_422() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        api.create(managerId, "바톤", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void 크루원_역할이나_매장_연결_전_단계면_403() throws Exception {
        long crewId = onboarding.userWithRole("CREW");
        api.create(crewId, "바톤", UUID.randomUUID().toString()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        long newUserId = onboarding.newUser(); // CONSENT_REQUIRED
        api.create(newUserId, "바톤", UUID.randomUUID().toString()).andExpect(status().isForbidden());
    }

    @Test
    void 이미_매장이_있으면_409_ALREADY_IN_STORE() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        api.createStore(managerId, "바톤");
        api.create(managerId, "두번째", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_IN_STORE"));
    }

    @Test
    void 같은_키_재요청은_처음_결과_그대로_매장_1개() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        String key = UUID.randomUUID().toString();
        long before = storeRepository.count();

        JsonNode first = api.data(api.create(managerId, "바톤", key).andExpect(status().isOk()));
        JsonNode second = api.data(api.create(managerId, "바톤", key).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(storeRepository.count()).isEqualTo(before + 1);
        assertThat(activeMembershipCount(managerId)).isEqualTo(1);

        api.create(managerId, "다른 이름", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 다른_키로_동시에_두_번_와도_매장은_1개() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        long before = storeRepository.count();
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return api.create(managerId, "바톤", UUID.randomUUID().toString()).andReturn().getResponse();
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
                statuses.add(response.getStatus());
                bodies.add(response.getContentAsString());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(bodies).anyMatch(body -> body.contains("ALREADY_IN_STORE"));
        } finally {
            executor.shutdownNow();
        }
        assertThat(storeRepository.count()).isEqualTo(before + 1);
        assertThat(activeMembershipCount(managerId)).isEqualTo(1);
    }

    @Test
    void 크루원은_초대_코드를_볼_수_없다() throws Exception {
        long managerId = onboarding.userWithRole("MANAGER");
        JsonNode store = api.createStore(managerId, "바톤");
        long crewId = onboarding.userWithRole("CREW");
        api.join(crewId, store.get("invite_code").asString()).andExpect(status().isOk());

        api.inviteCode(crewId, store.get("store_id").asLong())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        api.getStore(crewId, store.get("store_id").asLong()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member_count").value(2))
                .andExpect(jsonPath("$.data.my_role").value("CREW"));
    }

    @Test
    void 다른_매장의_정보_코드_구성원은_403() throws Exception {
        long managerA = onboarding.userWithRole("MANAGER");
        long managerB = onboarding.userWithRole("MANAGER");
        api.createStore(managerA, "A 매장");
        long storeB = api.createStore(managerB, "B 매장").get("store_id").asLong();

        api.getStore(managerA, storeB).andExpect(status().isForbidden());
        api.inviteCode(managerA, storeB).andExpect(status().isForbidden());
        api.members(managerA, storeB, null).andExpect(status().isForbidden());
        api.members(managerA, storeB, "substitute_candidates").andExpect(status().isForbidden());
    }

    private long activeMembershipCount(long userId) {
        return membershipRepository.findAll().stream()
                .filter(m -> Long.valueOf(userId).equals(m.getUserId()) && m.getState() == MembershipState.ACTIVE)
                .count();
    }
}
