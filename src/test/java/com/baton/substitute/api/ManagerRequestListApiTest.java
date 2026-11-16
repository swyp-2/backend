package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 매니저 요청 탭(요청·승인·거절): GET /stores/{storeId}/substitute-requests?status= */
@IntegrationTest
class ManagerRequestListApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired Clock clock;

    private SubstituteApi api;
    private StoreFixture store;

    @BeforeEach
    void setUp() throws Exception {
        OnboardingApi onboardingApi = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new SubstituteApi(mockMvc, jsonMapper, onboardingApi, new StoreApi(mockMvc, jsonMapper),
                shiftRepository, clock);
        store = api.store(3);
    }

    @Test
    void 상태별로_나눠_최신순으로_보여준다() throws Exception {
        long waiting = createRequest(store);
        long pendingOld = pending(store);
        long approved = pending(store);
        api.transitionOk(store.manager().userId(), approved, "approve");
        long rejected = pending(store);
        api.transitionOk(store.manager().userId(), rejected, "final-reject");
        long pendingNew = pending(store);

        JsonNode pendingPage = managerList(store, "PENDING_MANAGER", null);
        JsonNode approvedPage = managerList(store, "APPROVED", null);
        JsonNode rejectedPage = managerList(store, "REJECTED", null);

        assertThat(ids(pendingPage)).containsExactly(pendingNew, pendingOld).doesNotContain(waiting);
        assertThat(ids(approvedPage)).containsExactly(approved);
        assertThat(ids(rejectedPage)).containsExactly(rejected);

        JsonNode item = approvedPage.get("items").get(0);
        assertThat(item.get("status").asText()).isEqualTo("APPROVED");
        assertThat(item.get("requester").get("membership_id").asLong()).isEqualTo(store.crew(0).membershipId());
        assertThat(item.get("candidate").get("membership_id").asLong()).isEqualTo(store.crew(1).membershipId());
        assertThat(item.get("my_response").isNull()).isTrue();
        assertThat(item.get("shift").get("deleted").isNull()).isTrue();
        assertThat(approvedPage.get("has_more").asBoolean()).isFalse();
        assertThat(approvedPage.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void 다른_매장의_요청은_섞이지_않는다() throws Exception {
        long mine = pending(store);
        StoreFixture other = api.store(2);
        long others = pending(other);

        assertThat(ids(managerList(store, "PENDING_MANAGER", null))).containsExactly(mine);
        assertThat(ids(managerList(other, "PENDING_MANAGER", null))).containsExactly(others);
    }

    @Test
    void 카드_점은_조회한_매니저의_미확인_알림_기준이다() throws Exception {
        long requestId = pending(store);

        JsonNode before = managerList(store, "PENDING_MANAGER", null);
        assertThat(before.get("items").get(0).get("unchecked").asBoolean()).isTrue();

        api.check(store.manager().userId(), requestId).andExpect(status().isNoContent());

        JsonNode after = managerList(store, "PENDING_MANAGER", null);
        assertThat(after.get("items").get(0).get("unchecked").asBoolean()).isFalse();
    }

    @Test
    void 커서로_20개씩_나눠_가져온다() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            created.add(pending(store));
        }

        JsonNode page1 = managerList(store, "PENDING_MANAGER", null);
        assertThat(page1.get("items")).hasSize(20);
        assertThat(page1.get("has_more").asBoolean()).isTrue();

        JsonNode page2 = managerList(store, "PENDING_MANAGER", page1.get("next_cursor").asText());
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("has_more").asBoolean()).isFalse();

        List<Long> all = new ArrayList<>(ids(page1));
        all.addAll(ids(page2));
        assertThat(all).containsExactlyElementsOf(created.reversed());
    }

    @Test
    void 크루원은_403() throws Exception {
        api.managerList(store.crew(0).userId(), store.storeId(), "PENDING_MANAGER", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장_매니저는_403이고_요청_정보가_없다() throws Exception {
        pending(store);
        StoreFixture other = api.store(0);

        String body = api.managerList(other.manager().userId(), store.storeId(), "PENDING_MANAGER", null)
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("items").doesNotContain("requester");
    }

    @Test
    void 매장을_나간_매니저는_403() throws Exception {
        StoreFixture solo = api.store(0);
        mockMvc.perform(post("/stores/{id}/leave", solo.storeId())
                        .header(TestUserAuthenticationFilter.HEADER, solo.manager().userId()))
                .andExpect(status().isNoContent());

        api.managerList(solo.manager().userId(), solo.storeId(), "PENDING_MANAGER", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void status가_없거나_틀리면_422() throws Exception {
        api.managerList(store.manager().userId(), store.storeId(), null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("status"));
        api.managerList(store.manager().userId(), store.storeId(), "WAITING", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("status"));
        api.managerList(store.manager().userId(), store.storeId(), "approved", null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 커서_형식이_틀리면_422() throws Exception {
        api.managerList(store.manager().userId(), store.storeId(), "APPROVED", "not-a-cursor!")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("cursor"));
    }

    @Test
    void 응답에_user_id나_이메일이_없다() throws Exception {
        pending(store);

        String body = api.managerList(store.manager().userId(), store.storeId(), "PENDING_MANAGER", null)
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("user_id").doesNotContain("email").doesNotContain("provider");
    }

    /** crew(0)이 새 근무로 crew(1)에게 요청을 만든다(WAITING). */
    private long createRequest(StoreFixture fixture) throws Exception {
        long shiftId = api.shift(fixture.storeId(), fixture.crew(0).membershipId(), 1);
        return api.createRequest(fixture.crew(0).userId(), shiftId, List.of(fixture.crew(1).membershipId()))
                .get("id").asLong();
    }

    /** crew(1)이 수락해 매니저 승인 대기(PENDING_MANAGER)가 된 요청. */
    private long pending(StoreFixture fixture) throws Exception {
        long requestId = createRequest(fixture);
        api.transitionOk(fixture.crew(1).userId(), requestId, "accept");
        return requestId;
    }

    private JsonNode managerList(StoreFixture fixture, String status, String cursor) throws Exception {
        return api.data(api.managerList(fixture.manager().userId(), fixture.storeId(), status, cursor)
                .andExpect(status().isOk()));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }
}
