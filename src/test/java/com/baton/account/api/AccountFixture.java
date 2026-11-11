package com.baton.account.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.support.AuthApi;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 나가기·탈퇴 API 테스트 공용 준비 코드. 시각은 실제 시계 기준(토큰 검증과 맞추기 위해). */
final class AccountFixture {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    final MockMvc mockMvc;
    final OnboardingApi onboarding;
    final StoreApi stores;
    final AuthApi auth;
    private final AccessTokenService accessTokenService;
    private final MembershipRepository membershipRepository;
    private final ShiftRepository shiftRepository;

    AccountFixture(MockMvc mockMvc, JsonMapper jsonMapper, UserRepository userRepository,
                   MembershipRepository membershipRepository, ShiftRepository shiftRepository,
                   AccessTokenService accessTokenService) {
        this.mockMvc = mockMvc;
        this.onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        this.stores = new StoreApi(mockMvc, jsonMapper);
        this.auth = new AuthApi(mockMvc, jsonMapper);
        this.accessTokenService = accessTokenService;
        this.membershipRepository = membershipRepository;
        this.shiftRepository = shiftRepository;
    }

    /** 이름·역할까지 마친 회원(STORE_REQUIRED). */
    long userNamed(String name, String role) throws Exception {
        long userId = onboarding.newUser();
        onboarding.agree(userId, onboarding.allAgreements(userId, true)).andExpect(status().isOk());
        onboarding.setName(userId, name).andExpect(status().isOk());
        onboarding.selectRole(userId, role).andExpect(status().isOk());
        return userId;
    }

    /** 소셜 로그인으로 가입(소셜 계정·세션 생성) 후 이름·역할까지 마친 회원. */
    LoggedIn loginAndOnboard(String name, String role) throws Exception {
        String subject = "sub-" + UUID.randomUUID();
        String token = auth.login(subject).get("access_token").asString();
        long userId = Long.parseLong(accessTokenService.decoder().decode(token).getSubject());
        onboarding.agree(userId, onboarding.allAgreements(userId, true)).andExpect(status().isOk());
        onboarding.setName(userId, name).andExpect(status().isOk());
        onboarding.selectRole(userId, role).andExpect(status().isOk());
        return new LoggedIn(userId, subject, token);
    }

    record LoggedIn(long userId, String subject, String accessToken) {
    }

    /** 매니저가 매장을 만든다. */
    StoreInfo storeOf(long managerUserId, String name) throws Exception {
        JsonNode store = stores.createStore(managerUserId, name);
        return new StoreInfo(store.get("store_id").asLong(), store.get("membership_id").asLong(),
                store.get("invite_code").asString());
    }

    record StoreInfo(long storeId, long managerMembershipId, String code) {
    }

    long join(long crewUserId, String code) throws Exception {
        stores.join(crewUserId, code).andExpect(status().isOk());
        return activeMembershipOf(crewUserId);
    }

    long activeMembershipOf(long userId) {
        return membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).orElseThrow().getId();
    }

    long shift(long storeId, long membershipId, Instant start, Instant end) {
        return shiftRepository.saveAndFlush(Shift.schedule(storeId, membershipId, start, end, Instant.now()))
                .getId();
    }

    ResultActions leave(long userId, long storeId) throws Exception {
        return mockMvc.perform(post("/stores/{storeId}/leave", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId));
    }

    ResultActions withdraw(long userId) throws Exception {
        return mockMvc.perform(delete("/me").header(TestUserAuthenticationFilter.HEADER, userId));
    }

    ResultActions dayShifts(long userId, long storeId, LocalDate date) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}/shifts", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId).param("date", date.toString()));
    }
}
