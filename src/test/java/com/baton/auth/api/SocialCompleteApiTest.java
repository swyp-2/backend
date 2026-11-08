package com.baton.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.domain.SocialProvider;
import com.baton.support.AuthApi;
import com.baton.support.FakeSocialTokenVerifier;
import com.baton.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SocialCompleteApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccessTokenService accessTokenService;

    AuthApi authApi;

    @BeforeEach
    void setUp() {
        authApi = new AuthApi(mockMvc, jsonMapper);
    }

    @Test
    void 처음_보는_소셜_계정이면_가입_진행_중_회원을_만들고_토큰을_발급한다() throws Exception {
        String subject = newSubject();

        authApi.complete("google", FakeSocialTokenVerifier.idTokenFor(subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").isString())
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.data.expires_in").value(1_209_600))
                .andExpect(jsonPath("$.data.onboarding_stage").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.meta.server_time").isString())
                .andExpect(content().string(not(containsString(subject))));

        assertThat(socialAccountCount(SocialProvider.GOOGLE, subject)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select u.account_state from users u join social_accounts s on s.user_id = u.id
                where s.provider = 'GOOGLE' and s.provider_subject = ?""", String.class, subject))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 기존_회원은_같은_계정으로_다시_로그인하고_세션만_새로_생긴다() throws Exception {
        String subject = newSubject();
        long firstUser = userIdOf(authApi.login(subject));

        JsonNode second = authApi.login(subject);

        assertThat(userIdOf(second)).isEqualTo(firstUser);
        assertThat(socialAccountCount(SocialProvider.GOOGLE, subject)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from auth_sessions where user_id = ?",
                Integer.class, firstUser)).isEqualTo(2);
    }

    @Test
    void 세션_만료_시각은_토큰_exp와_같고_발급_후_14일이다() throws Exception {
        String accessToken = authApi.login(newSubject()).get("access_token").asString();
        Jwt jwt = accessTokenService.decoder().decode(accessToken);
        long sessionId = ((Number) jwt.getClaims().get("sid")).longValue();

        Instant sessionExpiresAt = jdbcTemplate.queryForObject(
                "select expires_at from auth_sessions where id = ?", Timestamp.class, sessionId).toInstant();

        assertThat(sessionExpiresAt).isEqualTo(jwt.getExpiresAt());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofDays(14));
    }

    @Test
    void refresh_엔드포인트는_없다() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refresh_token\":\"anything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void Apple과_Google은_같은_sub여도_별개_계정이다() throws Exception {
        String subject = newSubject();
        long googleUser = userIdOf(authApi.login(subject));

        JsonNode apple = authApi.data(authApi.complete("apple", FakeSocialTokenVerifier.idTokenFor(subject))
                .andExpect(status().isOk()));

        assertThat(userIdOf(apple)).isNotEqualTo(googleUser);
    }

    @Test
    void 검증에_실패한_토큰은_401이고_회원을_만들지_않는다() throws Exception {
        Integer before = jdbcTemplate.queryForObject("select count(*) from users", Integer.class);

        authApi.complete("google", "forged-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class)).isEqualTo(before);
    }

    @Test
    void id_token이_비어_있으면_422() throws Exception {
        authApi.complete("google", " ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("id_token"));
    }

    @Test
    void 지원하지_않는_제공자는_401() throws Exception {
        authApi.complete("kakao", FakeSocialTokenVerifier.idTokenFor(newSubject()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 만료된_Authorization_헤더가_붙어_와도_로그인은_된다() throws Exception {
        mockMvc.perform(post("/auth/google/complete")
                        .header("Authorization", "Bearer expired.or.garbage")
                        .contentType("application/json")
                        .content("{\"id_token\":\"" + FakeSocialTokenVerifier.idTokenFor(newSubject()) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 탈퇴로_소셜_연결이_지워진_계정은_신규_가입으로_처리된다() throws Exception {
        String subject = newSubject();
        long oldUser = userIdOf(authApi.login(subject));
        withdraw(oldUser);

        JsonNode again = authApi.login(subject);

        assertThat(userIdOf(again)).isNotEqualTo(oldUser);
        assertThat(again.get("onboarding_stage").asString()).isEqualTo("CONSENT_REQUIRED");
    }

    @Test
    void 같은_소셜_계정의_동시_첫_로그인_2건은_회원_1명으로_처리된다() throws Exception {
        String subject = newSubject();
        Integer usersBefore = jdbcTemplate.queryForObject("select count(*) from users", Integer.class);
        int requests = 2;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return authApi.complete("google", FakeSocialTokenVerifier.idTokenFor(subject)).andReturn();
                }));
            }
            ready.await();
            start.countDown();

            List<Long> userIds = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                userIds.add(userIdOf(jsonMapper.readTree(result.getResponse().getContentAsString()).get("data")));
            }
            assertThat(userIds).hasSize(2).containsOnly(userIds.getFirst());
        } finally {
            executor.shutdownNow();
        }
        assertThat(socialAccountCount(SocialProvider.GOOGLE, subject)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from users", Integer.class))
                .as("경합에서 진 쪽의 회원 행은 롤백되어 남지 않는다").isEqualTo(usersBefore + 1);
    }

    private long userIdOf(JsonNode data) {
        return Long.parseLong(accessTokenService.decoder().decode(data.get("access_token").asString()).getSubject());
    }

    private int socialAccountCount(SocialProvider provider, String subject) {
        return jdbcTemplate.queryForObject(
                "select count(*) from social_accounts where provider = ? and provider_subject = ?",
                Integer.class, provider.name(), subject);
    }

    private void withdraw(long userId) {
        jdbcTemplate.update("delete from social_accounts where user_id = ?", userId);
        jdbcTemplate.update("update users set account_state = 'WITHDRAWN', withdrawn_at = now() where id = ?", userId);
    }

    private static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }
}
