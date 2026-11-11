package com.baton.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 개발자 A 담당 API(인증·온보딩·매장·근무·계정)의 Swagger 문서가 태그·기능/화면 ID·필수 헤더·에러 응답을 갖추는지 확인.
 */
@IntegrationTest
class OpenApiDocsTest {

    /** {경로, 메서드, 태그, description 첫 줄(기능 ID / 화면 ID)} */
    private static final List<Op> OWNED = List.of(
            new Op("/auth/{provider}/complete", "post", "인증", "COM-02 / CO-01"),
            new Op("/auth/logout", "post", "인증", "PRO-03 / PR-04"),
            new Op("/me", "get", "온보딩", "COM-01, PRO-01 / CO-01~CO-05, PR-01"),
            new Op("/consent-documents", "get", "온보딩", "COM-03 / CO-02, CO-03"),
            new Op("/me/consents", "post", "온보딩", "COM-03 / CO-02"),
            new Op("/me/onboarding/name", "put", "온보딩", "COM-03 / CO-04"),
            new Op("/me/onboarding/role", "put", "온보딩", "COM-04 / CO-05"),
            new Op("/stores", "post", "매장", "STR-01 / ST-01"),
            new Op("/stores/{storeId}", "get", "매장", "PRO-01 / PR-02"),
            new Op("/stores/{storeId}/invite-code", "get", "매장", "STR-02 / ST-02, PR-02"),
            new Op("/memberships/join", "post", "매장", "STR-03 / ST-03"),
            new Op("/stores/{storeId}/members", "get", "매장", "PRO-01, SCH-03, SUB-01 / PR-02, ED-01, RQ-01"),
            new Op("/stores/{storeId}/shifts", "get", "근무",
                    "SCH-01 / CA-01, CA-02 (month 조회), SCH-02 / CA-03 (date 조회)"),
            new Op("/shifts/{shiftId}", "get", "근무", "SCH-02 / SH-01, SH-02"),
            new Op("/shift-batches/preview", "post", "근무", "SCH-03, SCH-04, SCH-05 / ED-01, ED-02, ED-03"),
            new Op("/shift-batches", "post", "근무", "SCH-05 / ED-03"),
            new Op("/shifts/{shiftId}", "delete", "근무", "SCH-07 / SH-02"),
            new Op("/stores/{storeId}/leave", "post", "계정", "PRO-03 / PR-04"),
            new Op("/me", "delete", "계정", "PRO-03 / PR-04"));

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    private JsonNode docs;

    @BeforeEach
    void setUp() throws Exception {
        docs = jsonMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 담당_API는_태그_한국어_요약_기능_화면_ID를_가진다() {
        for (Op op : OWNED) {
            JsonNode operation = operation(op);

            assertThat(operation.get("tags").get(0).asString()).as(op.toString()).isEqualTo(op.tag());
            assertThat(operation.get("summary").asString()).as(op.toString()).isNotBlank().doesNotContain("\n");
            assertThat(operation.get("description").asString()).as(op.toString()).startsWith(op.ids() + "\n");
        }
    }

    @Test
    void 담당_API의_에러_응답은_모두_ErrorResponse_스키마다() {
        for (Op op : OWNED) {
            JsonNode responses = operation(op).get("responses");
            List<String> errors = new ArrayList<>();
            responses.propertyNames().forEach(code -> {
                if (code.startsWith("4") || code.startsWith("5")) {
                    errors.add(code);
                }
            });

            assertThat(errors).as(op.toString()).isNotEmpty();
            for (String code : errors) {
                assertThat(responses.get(code).toString()).as(op + " " + code).contains("ErrorResponse");
            }
        }
    }

    @Test
    void 소셜_로그인_외_담당_API는_인증_필요라_401을_문서화한다() {
        for (Op op : OWNED) {
            if (op.path().equals("/auth/{provider}/complete")) {
                continue;
            }
            assertThat(operation(op).get("responses").has("401")).as(op.toString()).isTrue();
        }
    }

    @Test
    void 소셜_로그인은_인증_없이_호출하고_제공자_공개키_실패를_500으로_문서화한다() {
        JsonNode complete = docs.get("paths").get("/auth/{provider}/complete").get("post");

        assertThat(complete.get("security").isArray()).isTrue();
        assertThat(complete.get("security")).isEmpty();
        assertThat(complete.get("responses").has("500")).isTrue();
        assertThat(parameter(complete, "provider").get("schema").get("enum").toString())
                .contains("google", "apple");
    }

    @Test
    void 중복_제출_방지_API는_Idempotency_Key_헤더가_필수다() {
        for (String path : List.of("/stores", "/shift-batches")) {
            JsonNode header = parameter(docs.get("paths").get(path).get("post"), "Idempotency-Key");

            assertThat(header.get("in").asString()).isEqualTo("header");
            assertThat(header.get("required").asBoolean()).as(path).isTrue();
        }
        assertThat(docs.get("paths").get("/shift-batches/preview").get("post").has("parameters")).isFalse();
    }

    @Test
    void 근무_삭제는_expected_version이_필수다() {
        JsonNode delete = docs.get("paths").get("/shifts/{shiftId}").get("delete");
        JsonNode expectedVersion = parameter(delete, "expected_version");

        assertThat(expectedVersion.get("in").asString()).isEqualTo("query");
        assertThat(expectedVersion.get("required").asBoolean()).isTrue();
        assertThat(delete.get("responses").get("409").get("description").asString()).contains("CHANGED");
    }

    @Test
    void 근무_목록_조회의_200은_월_응답과_날짜_응답_중_하나다() {
        JsonNode list = docs.get("paths").get("/stores/{storeId}/shifts").get("get");
        String ok = list.get("responses").get("200").toString();

        assertThat(ok).contains("oneOf", "ApiResponseShiftMonthResponse", "ApiResponseShiftDayResponse");
        assertThat(schema("ApiResponseShiftMonthResponse").get("properties").get("data").toString())
                .contains("ShiftMonthResponse");
        assertThat(schema("ApiResponseShiftDayResponse").get("properties").get("data").toString())
                .contains("ShiftDayResponse");
        assertThat(parameter(list, "scope").get("schema").get("enum").toString()).contains("mine", "all");
    }

    @Test
    void DTO_필드는_snake_case_설명_예시를_가진다() {
        Map<String, String> examples = Map.of(
                "CreateStoreResponse", "invite_code",
                "MeResponse", "next_screen",
                "ShiftBatchPreview", "preview_token",
                "ShiftDetailResponse", "allowed_actions",
                "ShiftBatchRepeat", "weekdays");
        examples.forEach((schemaName, field) -> {
            JsonNode property = schema(schemaName).get("properties").get(field);

            assertThat(property).as(schemaName + "." + field).isNotNull();
            assertThat(property.get("description").asString()).as(schemaName + "." + field).isNotBlank();
            assertThat(property.has("example")).as(schemaName + "." + field).isTrue();
        });
        // 예시 값은 API 계약 초안(06) 예시와 맞춘다
        assertThat(schema("ShiftBatchRepeat").get("properties").get("weekdays").get("example").toString())
                .isEqualTo("[1,3,5]");
        assertThat(schema("ShiftBatchPreview").get("properties").get("total_count").get("example").asInt())
                .isEqualTo(28);
    }

    @Test
    void test_프로필에는_X_Test_User_Id_보안_스킴이_없다() {
        assertThat(docs.get("components").get("securitySchemes").has(OpenApiConfig.TEST_USER_AUTH)).isFalse();
        assertThat(docs.toString()).doesNotContain("X-Test-User-Id");
    }

    private JsonNode operation(Op op) {
        JsonNode path = docs.get("paths").get(op.path());
        assertThat(path).as(op.path()).isNotNull();
        JsonNode operation = path.get(op.method());
        assertThat(operation).as(op.toString()).isNotNull();
        return operation;
    }

    private JsonNode schema(String name) {
        JsonNode schema = docs.get("components").get("schemas").get(name);
        assertThat(schema).as(name).isNotNull();
        return schema;
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.get("parameters")) {
            if (parameter.get("name").asString().equals(name)) {
                return parameter;
            }
        }
        throw new AssertionError("파라미터 없음: " + name);
    }

    private record Op(String path, String method, String tag, String ids) {

        @Override
        public String toString() {
            return method.toUpperCase() + " " + path;
        }
    }
}
