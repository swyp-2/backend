package com.baton.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대타 요청 API 호출 헬퍼와 매장 픽스처. 인증은 X-Test-User-Id(local/test 전용).
 * 근무 등록 API(A)가 아직 없어 근무는 리포지토리로 직접 만든다.
 */
public final class SubstituteApi {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final OnboardingApi onboardingApi;
    private final StoreApi storeApi;
    private final ShiftRepository shiftRepository;
    private final Clock clock;

    public SubstituteApi(MockMvc mockMvc, JsonMapper jsonMapper, OnboardingApi onboardingApi, StoreApi storeApi,
                         ShiftRepository shiftRepository, Clock clock) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.onboardingApi = onboardingApi;
        this.storeApi = storeApi;
        this.shiftRepository = shiftRepository;
        this.clock = clock;
    }

    /** 매장 구성원 한 명: 인증용 userId와 소속 id. */
    public record Member(long userId, long membershipId) {
    }

    /** 매니저 1명 + 크루원 crewCount명의 매장. */
    public record StoreFixture(long storeId, Member manager, List<Member> crews) {
        public Member crew(int index) {
            return crews.get(index);
        }
    }

    public StoreFixture store(int crewCount) throws Exception {
        long managerUserId = onboardingApi.userWithRole("MANAGER");
        JsonNode store = storeApi.createStore(managerUserId, "대타매장");
        List<Member> crews = new ArrayList<>();
        for (int i = 0; i < crewCount; i++) {
            long crewUserId = onboardingApi.userWithRole("CREW");
            JsonNode joined = storeApi.data(storeApi.join(crewUserId, store.get("invite_code").asText())
                    .andExpect(status().isOk()));
            crews.add(new Member(crewUserId, joined.get("membership_id").asLong()));
        }
        return new StoreFixture(store.get("store_id").asLong(),
                new Member(managerUserId, store.get("membership_id").asLong()), crews);
    }

    /**
     * 지금부터 약 daysLater일 뒤 시작하는 8시간 근무. 음수면 이미 시작한 근무.
     * 같은 담당자의 완전 동일 근무(shifts_identical_live_uk)가 되지 않게 호출마다 시작을 1분씩 민다.
     */
    public long shift(long storeId, long assigneeMembershipId, int daysLater) {
        Instant now = clock.instant();
        Instant start = now.plus(Duration.ofDays(daysLater))
                .plus(Duration.ofMinutes(SEQUENCE.incrementAndGet()))
                .truncatedTo(ChronoUnit.SECONDS);
        return shiftRepository.saveAndFlush(
                Shift.schedule(storeId, assigneeMembershipId, start, start.plus(Duration.ofHours(8)), now)).getId();
    }

    /** key가 null이면 Idempotency-Key 헤더를 보내지 않는다. shiftId·targets가 null이면 본문에서 뺀다. */
    public ResultActions create(long userId, Long shiftId, List<Long> targets, String key) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (shiftId != null) {
            body.put("shift_id", shiftId);
        }
        if (targets != null) {
            body.put("target_membership_ids", targets);
        }
        MockHttpServletRequestBuilder request = post("/substitute-requests")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    public ResultActions create(long userId, long shiftId, List<Long> targets) throws Exception {
        return create(userId, shiftId, targets, UUID.randomUUID().toString());
    }

    /** 요청을 만들고 상세(data)를 돌려준다. */
    public JsonNode createRequest(long userId, long shiftId, List<Long> targets) throws Exception {
        return data(create(userId, shiftId, targets).andExpect(status().isOk()));
    }

    public ResultActions detail(long userId, long requestId) throws Exception {
        return mockMvc.perform(get("/substitute-requests/{id}", requestId)
                .header(TestUserAuthenticationFilter.HEADER, userId));
    }

    /** box·cursor가 null이면 파라미터를 보내지 않는다. */
    public ResultActions list(long userId, String box, String cursor) throws Exception {
        MockHttpServletRequestBuilder request = get("/me/substitute-requests")
                .header(TestUserAuthenticationFilter.HEADER, userId);
        if (box != null) {
            request.param("box", box);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    public JsonNode data(ResultActions result) throws Exception {
        return body(result).get("data");
    }

    public JsonNode body(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
