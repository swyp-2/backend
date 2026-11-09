package com.baton.auth.api;

import static com.baton.support.AuthApi.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.application.AccessTokenService;
import com.baton.member.domain.Role;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.AuthApi;
import com.baton.support.IntegrationTest;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class LogoutApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccessTokenService accessTokenService;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired Clock clock;

    AuthApi authApi;

    @BeforeEach
    void setUp() {
        authApi = new AuthApi(mockMvc, jsonMapper);
    }

    @Test
    void 로그아웃하면_204이고_같은_액세스_토큰은_즉시_401() throws Exception {
        String subject = newSubject();
        JsonNode thisDevice = authApi.login(subject);
        JsonNode otherDevice = authApi.login(subject);
        long userId = userIdOf(thisDevice);
        Membership membership = membershipRepository.saveAndFlush(Membership.join(
                storeRepository.saveAndFlush(Store.open("로그아웃매장", clock.instant())).getId(),
                userId, Role.CREW, "#AABBCC", clock.instant()));

        mockMvc.perform(post("/auth/logout").header("Authorization", bearer(accessOf(thisDevice))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/test-auth/whoami").header("Authorization", bearer(accessOf(thisDevice))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        // 다른 기기 세션, 계정, 매장 소속은 그대로
        mockMvc.perform(get("/test-auth/whoami").header("Authorization", bearer(accessOf(otherDevice))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select account_state from users where id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(membershipRepository.findById(membership.getId()).orElseThrow().getState())
                .isEqualTo(MembershipState.ACTIVE);
    }

    @Test
    void 토큰_없이_로그아웃하면_401() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    private String accessOf(JsonNode tokens) {
        return tokens.get("access_token").asString();
    }

    private long userIdOf(JsonNode tokens) {
        return Long.parseLong(accessTokenService.decoder().decode(accessOf(tokens)).getSubject());
    }

    private static String newSubject() {
        return "sub-" + UUID.randomUUID();
    }
}
