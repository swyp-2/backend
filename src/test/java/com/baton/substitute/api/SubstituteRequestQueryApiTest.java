package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
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

@IntegrationTest
class SubstituteRequestQueryApiTest {

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
        store = api.store(4);
    }

    // 상세 ---------------------------------------------------------------

    @Test
    void 지정_대상은_수락_거절_행동과_본인_응답만_본다() throws Exception {
        long requestId = createRequest(0, 1, 2);

        JsonNode data = api.data(api.detail(store.crew(1).userId(), requestId).andExpect(status().isOk()));

        assertThat(data.get("my_relation").asText()).isEqualTo("TARGET");
        assertThat(texts(data.get("allowed_actions"))).containsExactly("accept", "reject");
        assertThat(data.get("recipients")).hasSize(1);
        assertThat(data.get("recipients").get(0).get("membership_id").asLong())
                .isEqualTo(store.crew(1).membershipId());
    }

    @Test
    void 매니저는_WAITING에서_행동_없이_전체_대상을_본다() throws Exception {
        long requestId = createRequest(0, 1, 2);

        JsonNode data = api.data(api.detail(store.manager().userId(), requestId).andExpect(status().isOk()));

        assertThat(data.get("my_relation").asText()).isEqualTo("MANAGER");
        assertThat(data.get("allowed_actions")).isEmpty();
        assertThat(data.get("recipients")).hasSize(2);
    }

    @Test
    void 요청자는_취소_행동과_전체_대상을_본다() throws Exception {
        long requestId = createRequest(0, 1, 2);

        JsonNode data = api.data(api.detail(store.crew(0).userId(), requestId).andExpect(status().isOk()));

        assertThat(texts(data.get("allowed_actions"))).containsExactly("cancel");
        assertThat(data.get("recipients")).hasSize(2);
    }

    @Test
    void 같은_매장이라도_관계없는_크루원은_403() throws Exception {
        long requestId = createRequest(0, 1);

        api.detail(store.crew(3).userId(), requestId)
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장_사용자는_403() throws Exception {
        long requestId = createRequest(0, 1);
        StoreFixture other = api.store(0);

        api.detail(other.manager().userId(), requestId)
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_요청은_404() throws Exception {
        api.detail(store.crew(0).userId(), Long.MAX_VALUE)
                .andExpect(status().isNotFound());
    }

    @Test
    void 요청_후_근무가_삭제되면_shift_deleted가_true() throws Exception {
        JsonNode created = api.createRequest(store.crew(0).userId(),
                api.shift(store.storeId(), store.crew(0).membershipId(), 1), List.of(store.crew(1).membershipId()));
        Shift shift = shiftRepository.findById(created.get("shift").get("shift_id").asLong()).orElseThrow();
        shift.delete(clock.instant());
        shiftRepository.saveAndFlush(shift);

        api.detail(store.crew(0).userId(), created.get("id").asLong())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shift.deleted").value(true))
                .andExpect(jsonPath("$.data.shift.starts_at").value(created.get("shift").get("starts_at").asText()));
    }

    @Test
    void 응답에_user_id나_이메일이_없다() throws Exception {
        long requestId = createRequest(0, 1);

        String body = api.detail(store.crew(0).userId(), requestId).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("user_id").doesNotContain("email").doesNotContain("provider");
    }

    // 목록 ---------------------------------------------------------------

    @Test
    void 보낸_요청과_받은_요청을_나눠서_최신순으로_보여준다() throws Exception {
        long first = createRequest(0, 1);
        long second = createRequest(0, 1, 2);

        JsonNode sent = api.data(api.list(store.crew(0).userId(), "sent", null).andExpect(status().isOk()));
        JsonNode receivedByCrew1 = api.data(api.list(store.crew(1).userId(), "received", null)
                .andExpect(status().isOk()));
        JsonNode receivedByCrew2 = api.data(api.list(store.crew(2).userId(), "received", null)
                .andExpect(status().isOk()));
        JsonNode sentByCrew1 = api.data(api.list(store.crew(1).userId(), "sent", null).andExpect(status().isOk()));

        assertThat(ids(sent)).containsExactly(second, first);
        assertThat(ids(receivedByCrew1)).containsExactly(second, first);
        assertThat(ids(receivedByCrew2)).containsExactly(second);
        assertThat(ids(sentByCrew1)).isEmpty();
        assertThat(sent.get("has_more").asBoolean()).isFalse();
        assertThat(sent.get("next_cursor").isNull()).isTrue();
        assertThat(sent.get("items").get(0).get("my_response").isNull()).isTrue();
        assertThat(receivedByCrew1.get("items").get(0).get("my_response").asText()).isEqualTo("PENDING");
        assertThat(receivedByCrew1.get("items").get(0).get("requester").get("membership_id").asLong())
                .isEqualTo(store.crew(0).membershipId());
    }

    @Test
    void 커서로_20개씩_나눠_가져온다() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(createRequest(0, 1));
        }

        JsonNode page1 = api.data(api.list(store.crew(1).userId(), "received", null).andExpect(status().isOk()));
        assertThat(page1.get("items")).hasSize(20);
        assertThat(page1.get("has_more").asBoolean()).isTrue();

        JsonNode page2 = api.data(api.list(store.crew(1).userId(), "received", page1.get("next_cursor").asText())
                .andExpect(status().isOk()));
        assertThat(page2.get("items")).hasSize(5);
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        assertThat(page2.get("next_cursor").isNull()).isTrue();

        List<Long> all = new ArrayList<>(ids(page1));
        all.addAll(ids(page2));
        assertThat(all).containsExactlyElementsOf(created.reversed());
    }

    @Test
    void box가_없거나_틀리면_422() throws Exception {
        api.list(store.crew(0).userId(), null, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("box"));
        api.list(store.crew(0).userId(), "all", null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 커서_형식이_틀리면_422() throws Exception {
        api.list(store.crew(0).userId(), "sent", "not-a-cursor!")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("cursor"));
    }

    @Test
    void 매니저는_목록을_볼_수_없다() throws Exception {
        api.list(store.manager().userId(), "sent", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void 소속이_없으면_403() throws Exception {
        long noStoreUser = new OnboardingApi(mockMvc, jsonMapper, userRepository).userWithRole("CREW");

        api.list(noStoreUser, "sent", null)
                .andExpect(status().isForbidden());
    }

    /** crew(requester)가 새 근무로 crew(targets...)에게 요청을 만든다. */
    private long createRequest(int requester, int... targets) throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(requester).membershipId(), 1);
        List<Long> targetIds = new ArrayList<>();
        for (int target : targets) {
            targetIds.add(store.crew(target).membershipId());
        }
        return api.createRequest(store.crew(requester).userId(), shiftId, targetIds).get("id").asLong();
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
