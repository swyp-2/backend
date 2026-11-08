package com.baton.common;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@WithMockUser
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void BusinessException은_코드의_HTTP_상태와_current_state로_응답한다() throws Exception {
        mockMvc.perform(get("/test-errors/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_STATE_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.REQUEST_STATE_CONFLICT.getDefaultMessage()))
                .andExpect(jsonPath("$.error.field_errors").isEmpty())
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.current_state").value("PENDING_MANAGER"));
    }

    @Test
    void 본문_검증_실패는_422와_snake_case_field_errors() throws Exception {
        mockMvc.perform(post("/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"store_name": " ", "max_count": 0}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[*].field").value(containsInAnyOrder("store_name", "max_count")))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.current_state").doesNotExist());
    }

    @Test
    void 정상_본문은_snake_case로_읽고_알_수_없는_필드는_무시한다() throws Exception {
        mockMvc.perform(post("/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"store_name": "강남점", "max_count": 3, "unknown_field": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.store_name").value("강남점"))
                .andExpect(jsonPath("$.data.max_count").value(3))
                .andExpect(jsonPath("$.meta.server_time").value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    void 읽을_수_없는_본문은_422() throws Exception {
        mockMvc.perform(post("/test-errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("body"));
    }

    @Test
    void 파라미터_제약_위반은_422() throws Exception {
        mockMvc.perform(get("/test-errors/param").param("pageSize", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("page_size"));
    }

    @Test
    void 낙관적_락_충돌은_409_SHIFT_STATE_CONFLICT() throws Exception {
        mockMvc.perform(get("/test-errors/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_STATE_CONFLICT"));
    }

    @Test
    void 알_수_없는_예외는_500_INTERNAL_ERROR이고_내부_정보를_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/test-errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.INTERNAL_ERROR.getDefaultMessage()))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void 없는_경로는_404_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
