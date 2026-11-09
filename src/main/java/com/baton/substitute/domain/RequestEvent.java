package com.baton.substitute.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요청 진행 기록 한 건(요청·수락·거절·취소·철회·승인·종료). 추가만 하고 수정하지 않는다.
 */
@Getter
@Entity
@Table(name = "request_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "request_events_seq")
    @SequenceGenerator(name = "request_events_seq", sequenceName = "request_events_seq", allocationSize = 50)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false)
    private Long requestId;

    /** 행위자 소속. 만료 등 시스템 처리면 null. */
    @Column(name = "actor_membership_id", updatable = false)
    private Long actorMembershipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private RequestEventKind kind;

    /** 요청 생성이면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 30, updatable = false)
    private SubstituteStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", nullable = false, length = 30, updatable = false)
    private SubstituteStatus afterStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static RequestEvent record(Long requestId, Long actorMembershipId, RequestEventKind kind,
                                      SubstituteStatus beforeStatus, SubstituteStatus afterStatus, Instant now) {
        RequestEvent event = new RequestEvent();
        event.requestId = Objects.requireNonNull(requestId);
        event.actorMembershipId = actorMembershipId;
        event.kind = Objects.requireNonNull(kind);
        event.beforeStatus = beforeStatus;
        event.afterStatus = Objects.requireNonNull(afterStatus);
        event.occurredAt = Objects.requireNonNull(now);
        return event;
    }
}
