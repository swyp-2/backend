package com.baton.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증 API 호출 헬퍼.
 */
public final class AuthApi {

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;

    public AuthApi(MockMvc mockMvc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
    }

    public ResultActions complete(String provider, String idToken) throws Exception {
        return mockMvc.perform(post("/auth/{provider}/complete", provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("id_token", idToken))));
    }

    public ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("refresh_token", refreshToken))));
    }

    /** Google로 로그인해 data 노드(access_token, refresh_token, ...)를 돌려준다. */
    public JsonNode login(String subject) throws Exception {
        return data(complete("google", FakeSocialTokenVerifier.idTokenFor(subject)).andExpect(status().isOk()));
    }

    public JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    public static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
