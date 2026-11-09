package com.baton.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * prod 프로필에는 X-Test-User-Id 인증이 존재하지 않아야 한다.
 * 데이터소스는 Testcontainers가 덮어쓰고, 나머지 필수 환경변수는 더미 값을 넣는다.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:postgresql://unused:5432/unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "BATON_JWT_SECRET=prod-profile-test-secret-0123456789abcdef",
        "BATON_GOOGLE_CLIENT_IDS=prod-google-client",
        "BATON_APPLE_CLIENT_IDS=prod.apple.bundle",
        "BATON_SHIFT_PREVIEW_SECRET=prod-profile-test-preview-secret-0123456789",
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Import(TestcontainersConfig.class)
class ProdProfileSecurityTest {

    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    @Test
    void 테스트_사용자_인증_필터_빈이_없다() {
        assertThat(context.getBeanNamesForType(TestUserAuthenticationFilter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TestUserAuthConfig.class)).isEmpty();
    }

    @Test
    void X_Test_User_Id_헤더로는_인증되지_않는다() throws Exception {
        long userId = userRepository.saveAndFlush(User.signUp()).getId();

        mockMvc.perform(post("/auth/logout").header(TestUserAuthenticationFilter.HEADER, userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
