package com.baton.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.substitute.domain.RequestEventKind;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    @Test
    void 생성_후_정확히_7일에_만료된다() {
        Notification notification = Notification.of(1L, 2L, 3L, RequestEventKind.CANDIDATE_ACCEPTED, NOW);

        assertThat(notification.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(168)));
        assertThat(notification.isExpired(NOW.plus(Duration.ofHours(168)).minusMillis(1))).isFalse();
        assertThat(notification.isExpired(NOW.plus(Duration.ofHours(168)))).isTrue();
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    void 만료와_근무_삭제_이벤트로는_알림을_만들지_않는다() {
        assertThatThrownBy(() -> Notification.of(1L, 2L, 3L, RequestEventKind.REQUEST_EXPIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notification.of(1L, 2L, 3L, RequestEventKind.SOURCE_DELETED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
