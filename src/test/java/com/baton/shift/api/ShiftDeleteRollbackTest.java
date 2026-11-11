package com.baton.shift.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.support.IntegrationTest;
import com.baton.support.OnboardingApi;
import com.baton.support.StoreApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 대타 요청 종료(B)가 실패하면 근무 삭제도 함께 롤백된다(SCH-07 완료 기준, 부분 반영 금지). */
@IntegrationTest
class ShiftDeleteRollbackTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired UserRepository userRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean SubstituteRequestLifecycle substituteRequestLifecycle;

    @Test
    void 요청_종료에서_예외가_나면_근무는_삭제되지_않고_기록도_없다() throws Exception {
        given(substituteRequestLifecycle.closeBySourceDeleted(anyLong(), anyLong()))
                .willThrow(new IllegalStateException("B 처리 실패"));
        OnboardingApi onboarding = new OnboardingApi(mockMvc, jsonMapper, userRepository);
        StoreApi api = new StoreApi(mockMvc, jsonMapper);
        long manager = onboarding.newUser();
        onboarding.agree(manager, onboarding.allAgreements(manager, true)).andExpect(status().isOk());
        onboarding.setName(manager, "최매니저").andExpect(status().isOk());
        onboarding.selectRole(manager, "MANAGER").andExpect(status().isOk());
        JsonNode store = api.createStore(manager, "롤백 카페");
        long storeId = store.get("store_id").asLong();
        long managerMembership = membershipRepository.findByUserIdAndState(manager, MembershipState.ACTIVE)
                .orElseThrow().getId();
        Instant start = Instant.now().plus(Duration.ofDays(2));
        long shiftId = shiftRepository.saveAndFlush(Shift.schedule(storeId, managerMembership, start,
                start.plus(Duration.ofHours(4)), Instant.now())).getId();

        mockMvc.perform(delete("/shifts/{shiftId}", shiftId)
                        .header(TestUserAuthenticationFilter.HEADER, manager)
                        .param("expected_version", "0"))
                .andExpect(status().isInternalServerError());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT version, deleted_at FROM shifts WHERE id = ?",
                shiftId);
        assertThat(((Number) row.get("version")).longValue()).isZero();
        assertThat(row.get("deleted_at")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shift_events WHERE shift_id = ?", Long.class,
                shiftId)).isZero();
    }
}
