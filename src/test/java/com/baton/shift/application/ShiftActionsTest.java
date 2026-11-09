package com.baton.shift.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.baton.integration.ActiveRequestSummary;
import com.baton.member.domain.Role;
import org.junit.jupiter.api.Test;

class ShiftActionsTest {

    private static final ActiveRequestSummary OPEN = new ActiveRequestSummary(1L, "OPEN", true, false);
    private static final ActiveRequestSummary PENDING = new ActiveRequestSummary(1L, "PENDING_MANAGER", false, true);

    @Test
    void 크루원_내_근무_시작_전_요청_없음이면_대타_요청() {
        assertThat(ShiftActions.of(Role.CREW, true, false, null)).containsExactly("request_substitute");
    }

    @Test
    void 크루원_내_근무_시작_후_요청_없음이면_행동_없음() {
        assertThat(ShiftActions.of(Role.CREW, true, true, null)).isEmpty();
    }

    @Test
    void 크루원_내_근무에_활성_요청이_있으면_요청_보기만() {
        assertThat(ShiftActions.of(Role.CREW, true, false, OPEN)).containsExactly("view_request");
        assertThat(ShiftActions.of(Role.CREW, true, true, PENDING)).containsExactly("view_request");
    }

    @Test
    void 크루원이_타인_근무를_보면_행동_없음() {
        assertThat(ShiftActions.of(Role.CREW, false, false, null)).isEmpty();
        assertThat(ShiftActions.of(Role.CREW, false, false, OPEN)).isEmpty();
    }

    @Test
    void 매니저는_과거_근무도_삭제만_가능하고_대타_요청은_없다() {
        assertThat(ShiftActions.of(Role.MANAGER, false, true, null)).containsExactly("delete");
        // 매니저 본인이 담당자인 미래 근무라도 대타 요청 행동은 없다
        assertThat(ShiftActions.of(Role.MANAGER, true, false, null)).containsExactly("delete");
        assertThat(ShiftActions.of(Role.MANAGER, false, false, OPEN)).containsExactly("delete");
    }

    @Test
    void 매니저는_승인_대기_요청이_있으면_승인_요청_보기_추가() {
        assertThat(ShiftActions.of(Role.MANAGER, false, false, PENDING))
                .containsExactly("delete", "view_approval_request");
    }
}
