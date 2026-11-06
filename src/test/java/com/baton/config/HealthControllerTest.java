package com.baton.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class HealthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 인증_없이_헬스체크가_UP을_반환한다() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.meta.server_time").isString());
    }

    @Test
    void 보호된_경로는_인증이_없으면_401_UNAUTHENTICATED() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.field_errors").isEmpty())
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.current_state").doesNotExist());
    }

    @Test
    void API_문서는_인증_없이_열린다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("BATON API"));
    }
}
