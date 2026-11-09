package com.baton.notification.domain;

import com.baton.substitute.domain.RequestEventKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앱 내부 알림 한 건(이벤트 × 수신자). 생성 후 정확히 7일(168시간)에 만료된다.
 * 읽음은 요청 상태와 무관하고, 요청의 최신 상태는 조회할 때 요청 테이블에서 가져온다.
 */
@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    public static final Duration RETENTION = Duration.ofHours(168);

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notifications_seq")
    @SequenceGenerator(name = "notifications_seq", sequenceName = "notifications_seq", allocationSize = 50)
    private Long id;

    @Column(name = "recipient_membership_id", nullable = false, updatable = false)
    private Long recipientMembershipId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private Long requestId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private RequestEventKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    public static Notification of(Long recipientMembershipId, Long requestId, Long eventId, RequestEventKind kind,
                                  Instant now) {
        if (!kind.createsNotification()) {
            throw new IllegalArgumentException("알림을 만들지 않는 이벤트: " + kind);
        }
        Notification notification = new Notification();
        notification.recipientMembershipId = Objects.requireNonNull(recipientMembershipId);
        notification.requestId = Objects.requireNonNull(requestId);
        notification.eventId = Objects.requireNonNull(eventId);
        notification.kind = kind;
        notification.createdAt = Objects.requireNonNull(now);
        notification.expiresAt = now.plus(RETENTION);
        return notification;
    }

    /** 만료 시각 정각부터 만료로 본다. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
