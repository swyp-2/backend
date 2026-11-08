package com.baton.shift.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ShiftTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC);
    private final Instant now = Instant.now(clock);
    private final Instant start = Instant.parse("2026-11-10T00:00:00Z");

    private Shift shiftAssignedTo(long membershipId) {
        return Shift.schedule(1L, membershipId, start, start.plus(Duration.ofHours(8)), now);
    }

    @Test
    void 담당자를_변경한다() {
        Shift shift = shiftAssignedTo(10L);

        shift.reassign(10L, 20L);

        assertThat(shift.getAssigneeMembershipId()).isEqualTo(20L);
    }

    @Test
    void 삭제된_근무는_담당자를_변경할_수_없다() {
        Shift shift = shiftAssignedTo(10L);
        shift.delete(now);

        assertConflict(() -> shift.reassign(10L, 20L));
    }

    @Test
    void 현재_담당자가_from과_다르면_변경할_수_없다() {
        Shift shift = shiftAssignedTo(10L);

        assertConflict(() -> shift.reassign(11L, 20L));
        assertThat(shift.getAssigneeMembershipId()).isEqualTo(10L);
    }

    @Test
    void 같은_사람에게_변경할_수_없다() {
        Shift shift = shiftAssignedTo(10L);

        assertConflict(() -> shift.reassign(10L, 10L));
    }

    @Test
    void 삭제하면_삭제_시각을_남긴다() {
        Shift shift = shiftAssignedTo(10L);

        shift.delete(now);

        assertThat(shift.isDeleted()).isTrue();
        assertThat(shift.getDeletedAt()).isEqualTo(now);
    }

    @Test
    void 이미_삭제된_근무는_다시_삭제할_수_없다() {
        Shift shift = shiftAssignedTo(10L);
        shift.delete(now);

        assertConflict(() -> shift.delete(now.plusSeconds(1)));
        assertThat(shift.getDeletedAt()).isEqualTo(now);
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHIFT_STATE_CONFLICT);
    }
}
