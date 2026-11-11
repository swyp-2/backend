package com.baton.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.support.IntegrationTest;
import com.baton.support.MutableClock;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import com.baton.support.SubstituteApi;
import com.baton.support.SubstituteApi.StoreFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 빨간 점 두 단계: 하단 바 "요청" 탭 점(read_at), 요청 카드 점(checked_at).
 */
@IntegrationTest
class NotificationApiTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired MutableClock clock;

    private OnboardingApi onboardingApi;
    private SubstituteApi api;
    private StoreFixture store;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(NOW);
        onboardingApi = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        api = new SubstituteApi(mockMvc, jsonMapper, onboardingApi, new StoreApi(mockMvc, jsonMapper),
                shiftRepository, clock);
        store = api.store(3);
    }

    // 탭 점 ---------------------------------------------------------------

    @Test
    void 알림이_없으면_탭_점이_없다() throws Exception {
        api.indicator(store.crew(1).userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_unread").value(false))
                .andExpect(jsonPath("$.meta.server_time").exists());
    }

    @Test
    void 새_요청을_받으면_탭_점이_켜지고_요청_탭에_들어가면_꺼진다() throws Exception {
        newRequestTo(1);

        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();

        api.openRequestTab(store.crew(1).userId())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(api.hasUnread(store.crew(1).userId())).isFalse();
    }

    @Test
    void 요청_탭_진입_뒤_도착한_알림은_다시_점을_켠다() throws Exception {
        newRequestTo(1);
        api.openRequestTab(store.crew(1).userId()).andExpect(status().isNoContent());

        newRequestTo(1);

        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();
    }

    @Test
    void 요청_탭_진입은_여러_번_해도_같다() throws Exception {
        newRequestTo(1);

        api.openRequestTab(store.crew(1).userId()).andExpect(status().isNoContent());
        api.openRequestTab(store.crew(1).userId()).andExpect(status().isNoContent());

        assertThat(api.hasUnread(store.crew(1).userId())).isFalse();
    }

    @Test
    void 다른_사람의_요청_탭_진입은_내_점에_영향이_없다() throws Exception {
        newRequestTo(1, 2);

        api.openRequestTab(store.crew(2).userId()).andExpect(status().isNoContent());

        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();
        assertThat(api.hasUnread(store.crew(2).userId())).isFalse();
    }

    @Test
    void 첫_수락이_생기면_매니저에게도_탭_점이_켜진다() throws Exception {
        long requestId = newRequestTo(1);
        assertThat(api.hasUnread(store.manager().userId())).isFalse();

        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        assertThat(api.hasUnread(store.manager().userId())).isTrue();
    }

    @Test
    void 본인이_한_행동으로는_본인_점이_켜지지_않는다() throws Exception {
        long requestId = newRequestTo(1);
        api.check(store.crew(1).userId(), requestId).andExpect(status().isNoContent());

        api.transitionOk(store.crew(1).userId(), requestId, "accept");
        assertThat(api.hasUnread(store.crew(1).userId())).isFalse();
        assertThat(uncheckedByRequest(store.crew(1).userId(), "received")).containsEntry(requestId, false);

        api.transitionOk(store.manager().userId(), requestId, "approve");
        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();
        assertThat(api.hasUnread(store.crew(0).userId())).isTrue();
    }

    // 카드 점 -------------------------------------------------------------

    @Test
    void 받은_요청_카드에_점이_있고_요청_탭_진입으로는_지워지지_않는다() throws Exception {
        long requestId = newRequestTo(1);
        api.openRequestTab(store.crew(1).userId()).andExpect(status().isNoContent());

        assertThat(uncheckedByRequest(store.crew(1).userId(), "received")).containsEntry(requestId, true);
    }

    @Test
    void 상세를_열어_확인하면_그_카드_점만_지워진다() throws Exception {
        long first = newRequestTo(1);
        long second = newRequestTo(1);

        api.check(store.crew(1).userId(), first)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(uncheckedByRequest(store.crew(1).userId(), "received"))
                .containsEntry(first, false)
                .containsEntry(second, true);
    }

    @Test
    void 확인하면_그_요청의_탭_점_기준_읽음도_함께_처리된다() throws Exception {
        long requestId = newRequestTo(1);

        api.check(store.crew(1).userId(), requestId).andExpect(status().isNoContent());

        assertThat(api.hasUnread(store.crew(1).userId())).isFalse();
    }

    @Test
    void 확인_뒤_같은_요청에_새_알림이_오면_카드_점이_다시_켜진다() throws Exception {
        long requestId = newRequestTo(1);
        api.check(store.crew(0).userId(), requestId).andExpect(status().isNoContent());

        // 지정 대상이 수락하면 요청자에게 결과 알림이 간다
        api.transitionOk(store.crew(1).userId(), requestId, "accept");

        assertThat(uncheckedByRequest(store.crew(0).userId(), "sent")).containsEntry(requestId, true);
    }

    @Test
    void 관계없는_요청이나_없는_요청을_확인해도_204이고_아무것도_바뀌지_않는다() throws Exception {
        long requestId = newRequestTo(1);

        api.check(store.crew(2).userId(), requestId).andExpect(status().isNoContent());
        api.check(store.crew(1).userId(), Long.MAX_VALUE).andExpect(status().isNoContent());

        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();
        assertThat(uncheckedByRequest(store.crew(1).userId(), "received")).containsEntry(requestId, true);
    }

    // 7일 보관 --------------------------------------------------------------

    @Test
    void 생성_후_정확히_7일이_되면_탭_점과_카드_점에서_빠진다() throws Exception {
        long requestId = newRequestTo(1);

        clock.advance(Duration.ofHours(168).minusSeconds(1));
        assertThat(api.hasUnread(store.crew(1).userId())).isTrue();
        assertThat(uncheckedByRequest(store.crew(1).userId(), "received")).containsEntry(requestId, true);

        clock.advance(Duration.ofSeconds(1));
        assertThat(api.hasUnread(store.crew(1).userId())).isFalse();
        assertThat(uncheckedByRequest(store.crew(1).userId(), "received")).containsEntry(requestId, false);
    }

    // 권한 ----------------------------------------------------------------

    @Test
    void 매장_소속이_없으면_403() throws Exception {
        long loner = onboardingApi.userWithRole("CREW");

        api.indicator(loner).andExpect(status().isForbidden());
        api.openRequestTab(loner).andExpect(status().isForbidden());
        api.check(loner, 1L).andExpect(status().isForbidden());
    }

    @Test
    void 인증이_없으면_401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/me/notification-indicator"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------

    /** crew(0)이 본인 근무로 지정 대상(crew 인덱스)에게 요청을 보낸다. */
    private long newRequestTo(int... targetIndexes) throws Exception {
        long shiftId = api.shift(store.storeId(), store.crew(0).membershipId(), 2);
        List<Long> targets = java.util.Arrays.stream(targetIndexes)
                .mapToObj(i -> store.crew(i).membershipId())
                .toList();
        return api.createRequest(store.crew(0).userId(), shiftId, targets).get("id").asLong();
    }

    /** 목록 첫 페이지의 요청 id → unchecked(카드 점). */
    private Map<Long, Boolean> uncheckedByRequest(long userId, String box) throws Exception {
        JsonNode items = api.data(api.list(userId, box, null).andExpect(status().isOk())).get("items");
        Map<Long, Boolean> result = new HashMap<>();
        items.forEach(item -> result.put(item.get("id").asLong(), item.get("unchecked").asBoolean()));
        return result;
    }
}
