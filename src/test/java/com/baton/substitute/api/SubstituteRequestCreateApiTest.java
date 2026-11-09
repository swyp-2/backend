package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.notification.domain.Notification;
import com.baton.notification.domain.NotificationRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SubstituteRequestCreateApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired Clock clock;

    private SubstituteApi api;
    private StoreFixture store;
    private long requesterUserId;
    private long requesterId;
    private long shiftId;

    @BeforeEach
    void setUp() throws Exception {
        OnboardingApi onboardingApi = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new SubstituteApi(mockMvc, jsonMapper, onboardingApi, new StoreApi(mockMvc, jsonMapper),
                shiftRepository, clock);
        store = api.store(3);
        requesterUserId = store.crew(0).userId();
        requesterId = store.crew(0).membershipId();
        shiftId = api.shift(store.storeId(), requesterId, 1);
    }

    @Test
    void 요청을_만들면_WAITING_상세와_대상자_알림이_생긴다() throws Exception {
        long target1 = store.crew(1).membershipId();
        long target2 = store.crew(2).membershipId();

        JsonNode data = api.createRequest(requesterUserId, shiftId, List.of(target1, target2));

        assertThat(data.get("status").asText()).isEqualTo("WAITING");
        assertThat(data.get("terminal_reason").isNull()).isTrue();
        assertThat(data.get("my_relation").asText()).isEqualTo("REQUESTER");
        assertThat(data.get("requester").get("membership_id").asLong()).isEqualTo(requesterId);
        assertThat(data.get("requester").get("display_name").asText()).isEqualTo("김도연");
        assertThat(data.get("candidate").isNull()).isTrue();
        assertThat(data.get("shift").get("shift_id").asLong()).isEqualTo(shiftId);
        assertThat(data.get("shift").get("deleted").asBoolean()).isFalse();
        assertThat(data.get("recipients")).hasSize(2);
        assertThat(data.get("recipients").get(0).get("response").asText()).isEqualTo("PENDING");
        assertThat(data.get("allowed_actions")).extracting(JsonNode::asText).containsExactly("cancel");
        assertThat(data.get("events")).hasSize(1);
        assertThat(data.get("events").get(0).get("kind").asText()).isEqualTo("REQUEST_CREATED");

        long requestId = data.get("id").asLong();
        List<Notification> notifications = notificationRepository.findAll().stream()
                .filter(n -> Objects.equals(n.getRequestId(), requestId))
                .toList();
        assertThat(notifications).extracting(Notification::getRecipientMembershipId)
                .containsExactlyInAnyOrder(target1, target2);
        assertThat(notifications).allMatch(n -> n.getKind() == RequestEventKind.REQUEST_CREATED);
    }

    @Test
    void 같은_키로_다시_보내면_처음_결과를_그대로_돌려준다() throws Exception {
        List<Long> targets = List.of(store.crew(1).membershipId());
        JsonNode first = api.data(api.create(requesterUserId, shiftId, targets, "same-key").andExpect(status().isOk()));
        JsonNode second = api.data(api.create(requesterUserId, shiftId, targets, "same-key")
                .andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 같은_키에_다른_내용이면_422() throws Exception {
        api.create(requesterUserId, shiftId, List.of(store.crew(1).membershipId()), "reused").andExpect(status().isOk());

        api.create(requesterUserId, shiftId, List.of(store.crew(2).membershipId()), "reused")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 키가_없으면_422() throws Exception {
        api.create(requesterUserId, shiftId, List.of(store.crew(1).membershipId()), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void 본문_필드가_없으면_422() throws Exception {
        api.create(requesterUserId, null, null, "k1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 진행_중_요청이_있으면_409와_기존_요청_id() throws Exception {
        long existingId = api.createRequest(requesterUserId, shiftId, List.of(store.crew(1).membershipId()))
                .get("id").asLong();

        api.create(requesterUserId, shiftId, List.of(store.crew(2).membershipId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_REQUEST_EXISTS"))
                .andExpect(jsonPath("$.existing_request_id").value(existingId));
    }

    @Test
    void 다른_에러에는_existing_request_id가_없다() throws Exception {
        api.create(store.manager().userId(), shiftId, List.of(store.crew(1).membershipId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.existing_request_id").doesNotExist());
    }

    @Test
    void 매니저는_요청할_수_없다() throws Exception {
        long managerShift = api.shift(store.storeId(), store.manager().membershipId(), 1);

        api.create(store.manager().userId(), managerShift, List.of(store.crew(1).membershipId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 본인_담당이_아닌_근무는_403() throws Exception {
        long othersShift = api.shift(store.storeId(), store.crew(1).membershipId(), 1);

        api.create(requesterUserId, othersShift, List.of(store.crew(2).membershipId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장_사용자는_403() throws Exception {
        StoreFixture other = api.store(1);

        api.create(other.crew(0).userId(), shiftId, List.of(store.crew(1).membershipId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_근무는_404() throws Exception {
        api.create(requesterUserId, Long.MAX_VALUE, List.of(store.crew(1).membershipId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 삭제된_근무는_409_SHIFT_STATE_CONFLICT() throws Exception {
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.delete(clock.instant());
        shiftRepository.saveAndFlush(shift);

        api.create(requesterUserId, shiftId, List.of(store.crew(1).membershipId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_STATE_CONFLICT"));
    }

    @Test
    void 이미_시작한_근무는_409_REQUEST_EXPIRED() throws Exception {
        long started = api.shift(store.storeId(), requesterId, -1);

        api.create(requesterUserId, started, List.of(store.crew(1).membershipId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_EXPIRED"));
    }

    @Test
    void 대상이_없으면_422() throws Exception {
        expectInvalidTargets(List.of());
    }

    @Test
    void 같은_대상을_중복으로_보내면_422() throws Exception {
        long target = store.crew(1).membershipId();
        expectInvalidTargets(List.of(target, target));
    }

    @Test
    void 본인을_대상으로_보내면_422() throws Exception {
        expectInvalidTargets(List.of(requesterId));
    }

    @Test
    void 매니저를_대상으로_보내면_422() throws Exception {
        expectInvalidTargets(List.of(store.manager().membershipId()));
    }

    @Test
    void 다른_매장_구성원을_대상으로_보내면_422() throws Exception {
        StoreFixture other = api.store(1);
        expectInvalidTargets(List.of(store.crew(1).membershipId(), other.crew(0).membershipId()));
    }

    @Test
    void 실패한_요청은_아무것도_남기지_않는다() throws Exception {
        long before = notificationRepository.count();

        api.create(requesterUserId, shiftId, List.of(store.manager().membershipId()))
                .andExpect(status().isUnprocessableContent());

        assertThat(notificationRepository.count()).isEqualTo(before);
        // 같은 근무로 새 요청이 정상 생성된다(진행 중 요청이 남지 않았음)
        api.createRequest(requesterUserId, shiftId, List.of(store.crew(1).membershipId()));
    }

    @Test
    void 요청_시각은_서버_시각_기준이다() throws Exception {
        JsonNode data = api.createRequest(requesterUserId, shiftId, List.of(store.crew(1).membershipId()));

        assertThat(Duration.between(java.time.Instant.parse(data.get("created_at").asText()), clock.instant()))
                .isLessThan(Duration.ofMinutes(1));
    }

    private void expectInvalidTargets(List<Long> targets) throws Exception {
        api.create(requesterUserId, shiftId, targets)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("target_membership_ids"));
    }
}
