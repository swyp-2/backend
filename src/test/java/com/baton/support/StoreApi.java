package com.baton.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 매장·소속 API 호출 헬퍼. 인증은 X-Test-User-Id(local/test 전용). */
public final class StoreApi {

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;

    public StoreApi(MockMvc mockMvc, JsonMapper jsonMapper) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
    }

    /** key가 null이면 Idempotency-Key 헤더를 보내지 않는다. */
    public ResultActions create(long userId, String name, String key) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        MockHttpServletRequestBuilder request = post("/stores")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    /** 매장을 만들고 data 노드(store_id, name, membership_id, invite_code)를 돌려준다. */
    public JsonNode createStore(long userId, String name) throws Exception {
        return data(create(userId, name, UUID.randomUUID().toString()).andExpect(status().isOk()));
    }

    public ResultActions join(long userId, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        return mockMvc.perform(post("/memberships/join")
                .header(TestUserAuthenticationFilter.HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    public ResultActions getStore(long userId, long storeId) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}", storeId).header(TestUserAuthenticationFilter.HEADER, userId));
    }

    public ResultActions inviteCode(long userId, long storeId) throws Exception {
        return mockMvc.perform(get("/stores/{storeId}/invite-code", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId));
    }

    /** purpose가 null이면 쿼리 파라미터를 보내지 않는다. */
    public ResultActions members(long userId, long storeId, String purpose) throws Exception {
        MockHttpServletRequestBuilder request = get("/stores/{storeId}/members", storeId)
                .header(TestUserAuthenticationFilter.HEADER, userId);
        if (purpose != null) {
            request.param("purpose", purpose);
        }
        return mockMvc.perform(request);
    }

    public JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
