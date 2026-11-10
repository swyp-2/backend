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
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SubstituteRequestTransitionApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired Clock clock;

    private SubstituteApi api;
    private StoreFixture store;
    private long requesterUser;
    private long requesterId;
    private long target1User;
    private long target1Id;
    private long target2User;
    private long target2Id;
    private long managerUser;
    private long managerId;
    private long shiftId;
    private long requestId;

    @BeforeEach
    void setUp() throws Exception {
        api = new SubstituteApi(mockMvc, jsonMapper, new OnboardingApi(mockMvc, jsonMapper, userRepository),
                new StoreApi(mockMvc, jsonMapper), shiftRepository, clock);
        store = api.store(4);
        requesterUser = store.crew(0).userId();
        requesterId = store.crew(0).membershipId();
        target1User = store.crew(1).userId();
        target1Id = store.crew(1).membershipId();
        target2User = store.crew(2).userId();
        target2Id = store.crew(2).membershipId();
        managerUser = store.manager().userId();
        managerId = store.manager().membershipId();
        shiftId = api.shift(store.storeId(), requesterId, 1);
        requestId = api.createRequest(requesterUser, shiftId, List.of(target1Id, target2Id)).get("id").asLong();
    }

    // 수락 ---------------------------------------------------------------------------------------

    @Test
    void 수락하면_후보가_확정되고_담당자는_그대로이며_요청자_후보_매니저에게_알림() throws Exception {
        JsonNode data = api.transitionOk(target1User, requestId, "accept");

        assertThat(data.get("status").asText()).isEqualTo("PENDING_MANAGER");
        assertThat(data.get("candidate").get("membership_id").asLong()).isEqualTo(target1Id);
        assertThat(data.get("my_relation").asText()).isEqualTo("CANDIDATE");
        assertThat(data.get("allowed_actions")).extracting(JsonNode::asText).containsExactly("withdraw");
        assertThat(eventKinds(data)).containsExactly("REQUEST_CREATED", "CANDIDATE_ACCEPTED");
        assertThat(assignee()).isEqualTo(requesterId);
        assertThat(notified(RequestEventKind.CANDIDATE_ACCEPTED))
                .containsExactlyInAnyOrder(requesterId, target1Id, managerId);
    }

    @Test
    void 거절했던_대상도_WAITING이면_다시_수락할_수_있다() throws Exception {
        api.transitionOk(target1User, requestId, "reject");

        JsonNode data = api.transitionOk(target1User, requestId, "accept");

        assertThat(data.get("status").asText()).isEqualTo("PENDING_MANAGER");
        assertThat(data.get("candidate").get("membership_id").asLong()).isEqualTo(target1Id);
    }

    @Test
    void 후보가_정해진_뒤의_수락은_409와_최신_상태() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        api.transition(target2User, requestId, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_STATE_CONFLICT"))
                .andExpect(jsonPath("$.current_state").value("PENDING_MANAGER"));
    }

    @Test
    void 지정_대상이_아니면_수락_거절할_수_없다() throws Exception {
        long outsider = store.crew(3).userId();
        api.transition(outsider, requestId, "accept").andExpect(status().isForbidden());
        api.transition(outsider, requestId, "reject").andExpect(status().isForbidden());
        api.transition(requesterUser, requestId, "accept").andExpect(status().isForbidden());
        api.transition(managerUser, requestId, "accept").andExpect(status().isForbidden());
    }

    @Test
    void 다른_매장_사용자는_403() throws Exception {
        StoreFixture other = api.store(1);
        api.transition(other.crew(0).userId(), requestId, "accept").andExpect(status().isForbidden());
        api.transition(other.manager().userId(), requestId, "approve").andExpect(status().isForbidden());
    }

    @Test
    void 없는_요청은_404() throws Exception {
        api.transition(target1User, Long.MAX_VALUE, "accept").andExpect(status().isNotFound());
    }

    // 거절 ---------------------------------------------------------------------------------------

    @Test
    void 일부_거절은_WAITING을_유지하고_요청자와_매니저에게만_알림() throws Exception {
        api.transitionOk(target1User, requestId, "reject");

        JsonNode asRequester = api.data(api.detail(requesterUser, requestId));
        assertThat(asRequester.get("status").asText()).isEqualTo("WAITING");
        assertThat(asRequester.get("recipients")).extracting(r -> r.get("response").asText())
                .containsExactly("DECLINED", "PENDING");
        assertThat(notified(RequestEventKind.TARGET_DECLINED)).containsExactlyInAnyOrder(requesterId, managerId);
    }

    @Test
    void 모두_거절하면_ALL_DECLINED로_끝나고_다시_수락할_수_없다() throws Exception {
        api.transitionOk(target1User, requestId, "reject");
        JsonNode data = api.transitionOk(target2User, requestId, "reject");

        assertThat(data.get("status").asText()).isEqualTo("ALL_DECLINED");
        assertThat(data.get("closed_at").isNull()).isFalse();
        api.transition(target1User, requestId, "accept")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.current_state").value("ALL_DECLINED"));
    }

    @Test
    void 이미_거절한_대상의_재거절은_409() throws Exception {
        api.transitionOk(target1User, requestId, "reject");

        api.transition(target1User, requestId, "reject")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_STATE_CONFLICT"));
    }

    // 취소·철회 ------------------------------------------------------------------------------------

    @Test
    void 요청자는_승인_대기에서도_취소할_수_있고_직전_후보에게도_알림() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        JsonNode data = api.transitionOk(requesterUser, requestId, "cancel");

        assertThat(data.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(data.get("terminal_reason").isNull()).isTrue();
        assertThat(data.get("allowed_actions")).isEmpty();
        assertThat(assignee()).isEqualTo(requesterId);
        assertThat(notified(RequestEventKind.REQUEST_CANCELLED))
                .containsExactlyInAnyOrder(requesterId, target1Id, managerId);
    }

    @Test
    void 요청자가_아니면_취소할_수_없다() throws Exception {
        api.transition(target1User, requestId, "cancel").andExpect(status().isForbidden());
        api.transition(managerUser, requestId, "cancel").andExpect(status().isForbidden());
    }

    @Test
    void 후보가_철회하면_WITHDRAWN으로_끝나고_자동_재모집하지_않는다() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        JsonNode data = api.transitionOk(target1User, requestId, "withdraw");

        assertThat(data.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(assignee()).isEqualTo(requesterId);
        api.transition(target2User, requestId, "accept").andExpect(status().isConflict());
        assertThat(notified(RequestEventKind.CANDIDATE_WITHDRAWN))
                .containsExactlyInAnyOrder(requesterId, target1Id, managerId);
    }

    @Test
    void 후보가_아니면_철회할_수_없다() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        api.transition(target2User, requestId, "withdraw").andExpect(status().isForbidden());
        api.transition(requesterUser, requestId, "withdraw").andExpect(status().isForbidden());
    }

    // 매니저 승인·거절 -------------------------------------------------------------------------------

    @Test
    void 승인하면_그_근무만_담당자가_후보로_바뀌고_처리_매니저_포함_알림() throws Exception {
        long otherShift = api.shift(store.storeId(), requesterId, 2);
        api.transitionOk(target1User, requestId, "accept");

        JsonNode data = api.transitionOk(managerUser, requestId, "approve");

        assertThat(data.get("status").asText()).isEqualTo("APPROVED");
        assertThat(data.get("my_relation").asText()).isEqualTo("MANAGER");
        assertThat(data.get("allowed_actions")).isEmpty();
        assertThat(eventKinds(data)).containsExactly("REQUEST_CREATED", "CANDIDATE_ACCEPTED", "MANAGER_APPROVED");
        assertThat(assignee()).isEqualTo(target1Id);
        assertThat(shiftRepository.findById(otherShift).orElseThrow().getAssigneeMembershipId())
                .isEqualTo(requesterId);
        assertThat(notified(RequestEventKind.MANAGER_APPROVED))
                .containsExactlyInAnyOrder(requesterId, target1Id, managerId);
    }

    @Test
    void WAITING은_승인_최종거절할_수_없다() throws Exception {
        api.transition(managerUser, requestId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_STATE_CONFLICT"))
                .andExpect(jsonPath("$.current_state").value("WAITING"));
        api.transition(managerUser, requestId, "final-reject")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.current_state").value("WAITING"));
    }

    @Test
    void 크루원은_승인_최종거절할_수_없다() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        api.transition(requesterUser, requestId, "approve").andExpect(status().isForbidden());
        api.transition(target1User, requestId, "final-reject").andExpect(status().isForbidden());
    }

    @Test
    void 최종_거절하면_REJECTED이고_담당자는_그대로() throws Exception {
        api.transitionOk(target1User, requestId, "accept");

        JsonNode data = api.transitionOk(managerUser, requestId, "final-reject");

        assertThat(data.get("status").asText()).isEqualTo("REJECTED");
        assertThat(assignee()).isEqualTo(requesterId);
        assertThat(notified(RequestEventKind.MANAGER_REJECTED))
                .containsExactlyInAnyOrder(requesterId, target1Id, managerId);
    }

    @Test
    void 승인된_요청은_취소_철회_재승인할_수_없다() throws Exception {
        api.transitionOk(target1User, requestId, "accept");
        api.transitionOk(managerUser, requestId, "approve");

        api.transition(requesterUser, requestId, "cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.current_state").value("APPROVED"));
        api.transition(target1User, requestId, "withdraw").andExpect(status().isConflict());
        api.transition(managerUser, requestId, "approve").andExpect(status().isConflict());
        assertThat(assignee()).isEqualTo(target1Id);
    }

    @Test
    void 후보에게_완전히_같은_근무가_있으면_승인은_409_SHIFT_STATE_CONFLICT이고_아무것도_바뀌지_않는다() throws Exception {
        Shift source = shiftRepository.findById(shiftId).orElseThrow();
        shiftRepository.saveAndFlush(Shift.schedule(store.storeId(), target1Id, source.getStartsAt(),
                source.getEndsAt(), clock.instant()));
        api.transitionOk(target1User, requestId, "accept");

        api.transition(managerUser, requestId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_STATE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value(
                        "후보에게 이미 같은 시간의 근무가 있어요. 근무표를 확인해 주세요."));

        assertThat(api.data(api.detail(managerUser, requestId)).get("status").asText())
                .isEqualTo("PENDING_MANAGER");
        assertThat(assignee()).isEqualTo(requesterId);
        assertThat(notified(RequestEventKind.MANAGER_APPROVED)).isEmpty();
    }

    // 멱등성 ---------------------------------------------------------------------------------------

    @Test
    void 같은_키로_다시_보내면_처음_결과를_그대로_돌려준다() throws Exception {
        JsonNode first = api.data(api.transition(target1User, requestId, "accept", "accept-key")
                .andExpect(status().isOk()));
        JsonNode second = api.data(api.transition(target1User, requestId, "accept", "accept-key")
                .andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(notified(RequestEventKind.CANDIDATE_ACCEPTED)).hasSize(3);
    }

    @Test
    void 같은_키를_다른_요청에_쓰면_422() throws Exception {
        long otherShift = api.shift(store.storeId(), requesterId, 2);
        long otherRequest = api.createRequest(requesterUser, otherShift, List.of(target1Id)).get("id").asLong();
        api.transition(target1User, requestId, "reject", "reused").andExpect(status().isOk());

        api.transition(target1User, otherRequest, "reject", "reused")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 키가_없으면_422() throws Exception {
        api.transition(target1User, requestId, "accept", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.field_errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void 실패한_시도의_키로는_다시_시도할_수_있다() throws Exception {
        api.transition(managerUser, requestId, "approve", "retry-key").andExpect(status().isConflict());
        api.transitionOk(target1User, requestId, "accept");

        api.transition(managerUser, requestId, "approve", "retry-key").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------

    private long assignee() {
        return shiftRepository.findById(shiftId).orElseThrow().getAssigneeMembershipId();
    }

    private List<Long> notified(RequestEventKind kind) {
        return notificationRepository.findAll().stream()
                .filter(n -> Objects.equals(n.getRequestId(), requestId) && n.getKind() == kind)
                .map(Notification::getRecipientMembershipId)
                .toList();
    }

    private static List<String> eventKinds(JsonNode detail) {
        return detail.get("events").valueStream().map(e -> e.get("kind").asText()).toList();
    }
}
