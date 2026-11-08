package com.baton.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.member.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MembershipTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC);
    private final Instant now = Instant.now(clock);

    private Membership active() {
        return Membership.join(1L, 100L, Role.CREW, "#AABBCC", now);
    }

    @Test
    void 나가면_LEFT가_되고_계정_연결은_유지한다() {
        Membership membership = active();

        membership.leave(now);

        assertThat(membership.getState()).isEqualTo(MembershipState.LEFT);
        assertThat(membership.getLeftAt()).isEqualTo(now);
        assertThat(membership.getUserId()).isEqualTo(100L);
    }

    @Test
    void 활성이_아닌_소속은_나갈_수_없다() {
        Membership membership = active();
        membership.leave(now);

        assertThatThrownBy(() -> membership.leave(now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 활성_소속에서_탈퇴하면_WITHDRAWN이_되고_연결이_끊긴다() {
        Membership membership = active();

        membership.withdraw(now);

        assertThat(membership.getState()).isEqualTo(MembershipState.WITHDRAWN);
        assertThat(membership.getLeftAt()).isEqualTo(now);
        assertThat(membership.getUserId()).isNull();
    }

    @Test
    void 이미_나간_소속은_탈퇴_시_상태와_나간_시각을_유지하고_연결만_끊는다() {
        Membership membership = active();
        membership.leave(now);

        membership.withdraw(now.plusSeconds(60));

        assertThat(membership.getState()).isEqualTo(MembershipState.LEFT);
        assertThat(membership.getLeftAt()).isEqualTo(now);
        assertThat(membership.getUserId()).isNull();
    }
}
