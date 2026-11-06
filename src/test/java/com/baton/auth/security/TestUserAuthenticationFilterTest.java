package com.baton.auth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class TestUserAuthenticationFilterTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 헤더의_사용자로_인증한다() throws Exception {
        long userId = userRepository.saveAndFlush(User.signUp()).getId();

        mockMvc.perform(get("/test-auth/whoami").header(TestUserAuthenticationFilter.HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(userId))
                .andExpect(jsonPath("$.session_id").doesNotExist());
    }

    @Test
    void 없는_사용자면_401() throws Exception {
        mockMvc.perform(get("/test-auth/whoami").header(TestUserAuthenticationFilter.HEADER, Long.MAX_VALUE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/test-auth/whoami").header(TestUserAuthenticationFilter.HEADER, "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_사용자면_401() throws Exception {
        long userId = userRepository.saveAndFlush(User.signUp()).getId();
        jdbcTemplate.update("update users set account_state = 'WITHDRAWN', withdrawn_at = now() where id = ?", userId);

        mockMvc.perform(get("/test-auth/whoami").header(TestUserAuthenticationFilter.HEADER, userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
