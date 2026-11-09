package com.baton.substitute.domain;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
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
 * 대타 요청의 지정 대상 한 명. 요청 상태(WAITING 여부)는 호출자가 {@link SubstituteRequest}로 먼저 확인한다.
 */
@Getter
@Entity
@Table(name = "request_recipients")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "request_recipients_seq")
    @SequenceGenerator(name = "request_recipients_seq", sequenceName = "request_recipients_seq", allocationSize = 50)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false)
    private Long requestId;

    @Column(name = "target_membership_id", nullable = false, updatable = false)
    private Long targetMembershipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "response", nullable = false, length = 20)
    private RecipientResponse response;

    /** 마지막 수락·거절 시각. */
    @Column(name = "responded_at")
    private Instant respondedAt;

    /** 소속 해제로 응답 불가가 된 시각. */
    @Column(name = "unavailable_at")
    private Instant unavailableAt;

    public static RequestRecipient designate(Long requestId, Long targetMembershipId) {
        RequestRecipient recipient = new RequestRecipient();
        recipient.requestId = Objects.requireNonNull(requestId);
        recipient.targetMembershipId = Objects.requireNonNull(targetMembershipId);
        recipient.response = RecipientResponse.PENDING;
        return recipient;
    }

    /** 미응답이거나, 거절했지만 요청이 아직 WAITING인 대상(재수락)만 수락할 수 있다. */
    public boolean canAccept() {
        return response == RecipientResponse.PENDING || response == RecipientResponse.DECLINED;
    }

    /** 아직 응답하지 않은 대상만 거절할 수 있다. */
    public boolean canDecline() {
        return response == RecipientResponse.PENDING;
    }

    public void accept(Instant now) {
        if (!canAccept()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        response = RecipientResponse.ACCEPTED;
        respondedAt = Objects.requireNonNull(now);
    }

    public void decline(Instant now) {
        if (!canDecline()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        response = RecipientResponse.DECLINED;
        respondedAt = Objects.requireNonNull(now);
    }

    /** 소속 해제로 응답 불가. 이미 응답 불가면 그대로 둔다. */
    public void markUnavailable(Instant now) {
        if (response == RecipientResponse.UNAVAILABLE) {
            return;
        }
        response = RecipientResponse.UNAVAILABLE;
        unavailableAt = Objects.requireNonNull(now);
    }
}
