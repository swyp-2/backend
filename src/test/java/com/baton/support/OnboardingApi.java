package com.baton.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 온보딩 API 호출 헬퍼. 인증은 X-Test-User-Id(local/test 전용).
 */
public final class OnboardingApi {

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final UserRepository userRepository;

    public OnboardingApi(MockMvc mockMvc, JsonMapper jsonMapper, UserRepository userRepository) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.userRepository = userRepository;
    }

    /** 소셜 인증 직후 상태(CONSENT_REQUIRED)의 새 회원. */
    public long newUser() {
        return userRepository.saveAndFlush(User.signUp()).getId();
    }

    public ResultActions me(long userId) throws Exception {
        return mockMvc.perform(get("/me").header(TestUserAuthenticationFilter.HEADER, userId));
    }

    public ResultActions documents(long userId) throws Exception {
        return mockMvc.perform(get("/consent-documents").header(TestUserAuthenticationFilter.HEADER, userId));
    }

    public ResultActions agree(long userId, List<Map<String, Object>> agreements) throws Exception {
        return agreeRaw(userId, jsonMapper.writeValueAsString(Map.of("agreements", agreements)));
    }

    public ResultActions agreeRaw(long userId, String body) throws Exception {
        return mockMvc.perform(post("/me/consents")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    public ResultActions setName(long userId, String displayName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("display_name", displayName);
        return mockMvc.perform(put("/me/onboarding/name")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    public ResultActions selectRole(long userId, String role) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("role", role);
        return mockMvc.perform(put("/me/onboarding/role")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    public ResultActions getStore(long userId, long storeId) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}", storeId).header(TestUserAuthenticationFilter.HEADER, userId));
    }

    /** 현재 유효한 약관 목록(data 배열). */
    public JsonNode currentDocuments(long userId) throws Exception {
        return data(documents(userId).andExpect(status().isOk()));
    }

    /** 현재 유효 문서 전부에 대해 같은 값으로 동의 요청 본문을 만든다. */
    public List<Map<String, Object>> allAgreements(long userId, boolean optionalAgreed) throws Exception {
        List<Map<String, Object>> agreements = new ArrayList<>();
        for (JsonNode doc : currentDocuments(userId)) {
            boolean agreed = doc.get("required").asBoolean() || optionalAgreed;
            agreements.add(Map.of("document_id", doc.get("document_id").asLong(), "agreed", agreed));
        }
        return agreements;
    }

    /** CONSENT_REQUIRED → STORE_REQUIRED까지 진행. */
    public long userWithRole(String role) throws Exception {
        long userId = newUser();
        agree(userId, allAgreements(userId, true)).andExpect(status().isOk());
        setName(userId, "김도연").andExpect(status().isOk());
        selectRole(userId, role).andExpect(status().isOk());
        return userId;
    }

    public JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
