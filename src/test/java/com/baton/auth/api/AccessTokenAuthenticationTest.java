package com.baton.auth.api;

import static com.baton.support.AuthApi.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AccessTokenAuthenticationTest {

    private static final String PROTECTED = "/test-auth/whoami";

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccessTokenService accessTokenService;
    @Autowired Clock clock;

    AuthApi authApi;

    @BeforeEach
    void setUp() {
        authApi = new AuthApi(mockMvc, jsonMapper);
    }

    @Test
    void 유효한_토큰이면_AuthUser로_인증된다() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        long sessionId = sessionIdOf(tokens);

        call(tokens.get("access_token").asString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(userIdOf(tokens)))
                .andExpect(jsonPath("$.session_id").value(sessionId));
    }

    @Test
    void 발급_후_14일이_되기_직전까지는_인증된다() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        String almostExpired = accessTokenService.issue(userIdOf(tokens), sessionIdOf(tokens),
                clock.instant().minus(Duration.ofDays(14)).plusSeconds(60));

        call(almostExpired).andExpect(status().isOk());
    }

    @Test
    void 발급_후_14일이_지난_토큰은_401() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        String expired = accessTokenService.issue(userIdOf(tokens), sessionIdOf(tokens),
                clock.instant().minus(Duration.ofDays(14)).minusSeconds(1));

        assertUnauthenticated(call(expired));
    }

    @Test
    void 세션_만료_시각이_지나면_토큰이_남아_있어도_401() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        jdbcTemplate.update("update auth_sessions set expires_at = ? where id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), sessionIdOf(tokens));

        assertUnauthenticated(call(tokens.get("access_token").asString()));
    }

    @Test
    void 폐기된_세션의_토큰은_401() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        jdbcTemplate.update("update auth_sessions set revoked_at = now() where id = ?", sessionIdOf(tokens));

        assertUnauthenticated(call(tokens.get("access_token").asString()));
    }

    @Test
    void 탈퇴한_계정의_토큰은_401() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        jdbcTemplate.update("update users set account_state = 'WITHDRAWN', withdrawn_at = now() where id = ?",
                userIdOf(tokens));

        assertUnauthenticated(call(tokens.get("access_token").asString()));
    }

    @Test
    void 다른_사용자의_세션_id를_넣은_토큰은_401() throws Exception {
        JsonNode mine = authApi.login(newSubject());
        JsonNode other = authApi.login(newSubject());
        String mixed = accessTokenService.issue(userIdOf(mine), sessionIdOf(other));

        assertUnauthenticated(call(mixed));
    }

    @Test
    void 위조되거나_형식이_틀린_토큰은_401() throws Exception {
        String token = authApi.login(newSubject()).get("access_token").asString();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertUnauthenticated(call(tampered));
        assertUnauthenticated(call("not-a-jwt"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        assertUnauthenticated(mockMvc.perform(get(PROTECTED)));
    }

    private ResultActions call(String accessToken) throws Exception {
        return mockMvc.perform(get(PROTECTED).header("Authorization", bearer(accessToken)));
    }

    private static void assertUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.field_errors").isEmpty())
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    private long userIdOf(JsonNode tokens) {
        return Long.parseLong(accessTokenService.decoder().decode(tokens.get("access_token").asString()).getSubject());
    }

    private long sessionIdOf(JsonNode tokens) {
        return ((Number) accessTokenService.decoder().decode(tokens.get("access_token").asString())
                .getClaims().get("sid")).longValue();
    }

    private static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }
}
