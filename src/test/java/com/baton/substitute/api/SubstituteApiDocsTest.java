package com.baton.substitute.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Swagger(OpenAPI) 문서가 실제 API와 같은 모양(snake_case, 필수 헤더, 응답 스키마)으로 나오는지 확인.
 */
@IntegrationTest
class SubstituteApiDocsTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    private JsonNode docs;

    @BeforeEach
    void setUp() throws Exception {
        docs = jsonMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void 문서의_필드명은_실제_응답처럼_snake_case다() {
        assertThat(propertyNames("SubstituteRequestDetail"))
                .contains("terminal_reason", "my_relation", "allowed_actions", "created_at")
                .doesNotContain("allowedActions");
        assertThat(propertyNames("CreateSubstituteRequest")).containsExactlyInAnyOrder("shift_id",
                "target_membership_ids");
        // 전역 설정이라 A의 API 문서도 같이 맞춰진다
        assertThat(propertyNames("CreateStoreResponse")).contains("store_id", "invite_code");
    }

    @Test
    void 대타_요청_스키마는_고유한_이름으로_나온다() {
        JsonNode schemas = docs.get("components").get("schemas");
        assertThat(schemas.has("SubstituteRequestDetail")).isTrue();
        assertThat(schemas.has("SubstituteRequestPage")).isTrue();
        assertThat(schemas.has("SubstituteRequestSummary")).isTrue();
        assertThat(schemas.has("Detail")).isFalse();
        assertThat(schemas.has("Page")).isFalse();
    }

    @Test
    void 생성_API는_태그_필수_헤더_응답_스키마가_있다() {
        JsonNode create = docs.get("paths").get("/substitute-requests").get("post");

        assertThat(create.get("tags").get(0).asText()).isEqualTo("대타 요청");
        JsonNode header = parameter(create, "Idempotency-Key");
        assertThat(header.get("in").asText()).isEqualTo("header");
        assertThat(header.get("required").asBoolean()).isTrue();
        assertThat(create.get("responses").get("200").toString()).contains("SubstituteRequestDetail");
        assertThat(create.get("responses").get("409").toString()).contains("ErrorResponse");
    }

    @Test
    void 목록_API는_box가_필수이고_cursor는_선택이다() {
        JsonNode list = docs.get("paths").get("/me/substitute-requests").get("get");

        assertThat(parameter(list, "box").get("required").asBoolean()).isTrue();
        assertThat(parameter(list, "cursor").path("required").asBoolean(false)).isFalse();
        assertThat(list.get("responses").get("200").toString()).contains("SubstituteRequestPage");
        assertThat(docs.get("paths").get("/substitute-requests/{requestId}").get("get")).isNotNull();
    }

    @Test
    void 상태_전이_API_6개는_필수_헤더와_상세_응답_에러_스키마가_있다() {
        for (String action : List.of("accept", "reject", "cancel", "withdraw", "approve", "final-reject")) {
            JsonNode operation = docs.get("paths").get("/substitute-requests/{requestId}/" + action).get("post");

            assertThat(operation.get("tags").get(0).asText()).isEqualTo("대타 요청");
            assertThat(parameter(operation, "Idempotency-Key").get("required").asBoolean()).isTrue();
            assertThat(operation.get("responses").get("200").toString()).contains("SubstituteRequestDetail");
            assertThat(operation.get("responses").get("409").toString()).contains("ErrorResponse");
            assertThat(operation.has("requestBody")).isFalse();
        }
    }

    private List<String> propertyNames(String schema) {
        List<String> names = new ArrayList<>();
        docs.get("components").get("schemas").get(schema).get("properties").propertyNames().forEach(names::add);
        return names;
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.get("parameters")) {
            if (parameter.get("name").asText().equals(name)) {
                return parameter;
            }
        }
        throw new AssertionError("파라미터 없음: " + name);
    }
}
