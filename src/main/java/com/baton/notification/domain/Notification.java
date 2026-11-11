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
 * 읽음·확인은 요청 상태와 무관하다. 알림 목록 화면은 없고 빨간 점(탭·카드) 계산에만 쓴다(2026-11-11 Figma 기준).
 * 읽음·확인 처리는 리포지토리의 일괄 UPDATE로만 한다.
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

    /** 하단 바 "요청" 탭 점. 요청 탭 진입 시 채운다. */
    @Column(name = "read_at")
    private Instant readAt;

    /** 요청 카드 점. 그 요청 상세를 열 때 채운다(read_at도 함께). */
    @Column(name = "checked_at")
    private Instant checkedAt;

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
