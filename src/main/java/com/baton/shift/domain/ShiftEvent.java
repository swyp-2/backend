package com.baton.shift.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 근무 등록·삭제·담당자 변경의 내부 추적 기록. 변경 이력 UI는 제공하지 않는다.
 * before/after_value는 JSON 문자열, request_id는 B의 대타 요청 id(FK 없음).
 */
@Getter
@Entity
@Table(name = "shift_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "shift_events_seq")
    @SequenceGenerator(name = "shift_events_seq", sequenceName = "shift_events_seq", allocationSize = 50)
    private Long id;

    @Column(name = "shift_id", nullable = false, updatable = false)
    private Long shiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, length = 20, updatable = false)
    private ShiftEventKind eventKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value", updatable = false)
    private String beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value", updatable = false)
    private String afterValue;

    @Column(name = "actor_membership_id", updatable = false)
    private Long actorMembershipId;

    @Column(name = "request_id", updatable = false)
    private Long requestId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public static ShiftEvent record(Long shiftId, ShiftEventKind eventKind, String beforeValue, String afterValue,
                                    Long actorMembershipId, Long requestId, Instant now) {
        ShiftEvent event = new ShiftEvent();
        event.shiftId = shiftId;
        event.eventKind = eventKind;
        event.beforeValue = beforeValue;
        event.afterValue = afterValue;
        event.actorMembershipId = actorMembershipId;
        event.requestId = requestId;
        event.occurredAt = now;
        return event;
    }
}
