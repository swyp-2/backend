package com.baton.auth.api;

import static com.baton.support.AuthApi.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.application.RefreshTokens;
import com.baton.auth.domain.AuthSession;
import com.baton.auth.domain.AuthSessionRepository;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class RefreshApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccessTokenService accessTokenService;
    @Autowired AuthSessionRepository authSessionRepository;
    @Autowired Clock clock;

    AuthApi authApi;

    @BeforeEach
    void setUp() {
        authApi = new AuthApi(mockMvc, jsonMapper);
    }

    @Test
    void 회전하면_기존_세션은_폐기되고_새_토큰이_동작한다() throws Exception {
        JsonNode first = authApi.login(newSubject());
        String oldRefresh = first.get("refresh_token").asString();

        JsonNode rotated = authApi.data(authApi.refresh(oldRefresh)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expires_in").value(1800))
                .andExpect(jsonPath("$.data.onboarding_stage").value("CONSENT_REQUIRED")));

        assertThat(rotated.get("refresh_token").asString()).isNotEqualTo(oldRefresh);
        assertThat(sessionIdOf(rotated)).isNotEqualTo(sessionIdOf(first));
        assertThat(authSessionRepository.findById(sessionIdOf(first)).orElseThrow().getRevokedAt()).isNotNull();
        mockMvc.perform(get("/test-auth/whoami").header("Authorization", bearer(accessOf(rotated))))
                .andExpect(status().isOk());
        // 회전 전 액세스 토큰은 세션이 폐기되어 즉시 막힌다
        mockMvc.perform(get("/test-auth/whoami").header("Authorization", bearer(accessOf(first))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 폐기된_refresh_토큰을_재사용하면_그_사용자의_모든_세션을_폐기한다() throws Exception {
        String subject = newSubject();
        JsonNode deviceA = authApi.login(subject);
        JsonNode deviceB = authApi.login(subject);
        String stolen = deviceA.get("refresh_token").asString();
        JsonNode rotatedA = authApi.data(authApi.refresh(stolen).andExpect(status().isOk()));

        authApi.refresh(stolen)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        long userId = userIdOf(deviceA);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_sessions where user_id = ? and revoked_at is null", Integer.class, userId))
                .isZero();
        authApi.refresh(rotatedA.get("refresh_token").asString()).andExpect(status().isUnauthorized());
        authApi.refresh(deviceB.get("refresh_token").asString()).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/test-auth/whoami").header("Authorization", bearer(accessOf(deviceB))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 만료된_refresh_토큰은_401이고_다른_세션은_유지된다() throws Exception {
        JsonNode live = authApi.login(newSubject());
        long userId = userIdOf(live);
        String expiredToken = RefreshTokens.generate();
        Instant past = clock.instant().minus(Duration.ofDays(15));
        authSessionRepository.saveAndFlush(
                AuthSession.open(userId, RefreshTokens.hash(expiredToken), past.plus(Duration.ofDays(14)), past));

        authApi.refresh(expiredToken).andExpect(status().isUnauthorized());

        authApi.refresh(live.get("refresh_token").asString()).andExpect(status().isOk());
    }

    @Test
    void 모르는_refresh_토큰은_401() throws Exception {
        authApi.refresh(RefreshTokens.generate())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 탈퇴한_계정의_refresh는_401() throws Exception {
        JsonNode tokens = authApi.login(newSubject());
        jdbcTemplate.update("update users set account_state = 'WITHDRAWN', withdrawn_at = now() where id = ?",
                userIdOf(tokens));

        authApi.refresh(tokens.get("refresh_token").asString()).andExpect(status().isUnauthorized());
    }

    private String accessOf(JsonNode tokens) {
        return tokens.get("access_token").asString();
    }

    private long userIdOf(JsonNode tokens) {
        return Long.parseLong(accessTokenService.decoder().decode(accessOf(tokens)).getSubject());
    }

    private long sessionIdOf(JsonNode tokens) {
        return ((Number) accessTokenService.decoder().decode(accessOf(tokens)).getClaims().get("sid")).longValue();
    }

    private static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }
}
