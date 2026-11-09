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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대타 요청 한 건. 상태 전이 규칙(어느 상태에서 무엇을 할 수 있는지)만 여기서 보장한다.
 * 호출자가 요청자·지정 대상·소속 매니저인지 같은 관계 권한, 지정 대상 응답 집계는 서비스가 판단한다.
 *
 * <p>수락은 후보 확정일 뿐 근무 담당자를 바꾸지 않는다. 담당자 변경은 승인 시 서비스가 ShiftAssignment로 한다.
 * 동시 처리는 {@code @Version}으로 한 건만 성공시킨다.
 */
@Getter
@Entity
@Table(name = "substitute_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubstituteRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "substitute_requests_seq")
    @SequenceGenerator(name = "substitute_requests_seq", sequenceName = "substitute_requests_seq", allocationSize = 50)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "shift_id", nullable = false, updatable = false)
    private Long shiftId;

    @Column(name = "requester_membership_id", nullable = false, updatable = false)
    private Long requesterMembershipId;

    @Column(name = "candidate_membership_id")
    private Long candidateMembershipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SubstituteStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason", length = 30)
    private TerminalReason terminalReason;

    @Column(name = "source_starts_at", nullable = false, updatable = false)
    private Instant sourceStartsAt;

    @Column(name = "source_ends_at", nullable = false, updatable = false)
    private Instant sourceEndsAt;

    @Column(name = "source_version", nullable = false, updatable = false)
    private long sourceVersion;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * 새 요청(WAITING). 원본 근무 시각·버전은 생성 시점 스냅샷이다.
     * 본인 담당·시작 전·지정 대상 검증은 호출자가 먼저 한다.
     */
    public static SubstituteRequest open(Long storeId, Long shiftId, Long requesterMembershipId,
                                         Instant sourceStartsAt, Instant sourceEndsAt, long sourceVersion,
                                         Instant now) {
        SubstituteRequest request = new SubstituteRequest();
        request.storeId = Objects.requireNonNull(storeId);
        request.shiftId = Objects.requireNonNull(shiftId);
        request.requesterMembershipId = Objects.requireNonNull(requesterMembershipId);
        request.sourceStartsAt = Objects.requireNonNull(sourceStartsAt);
        request.sourceEndsAt = Objects.requireNonNull(sourceEndsAt);
        request.sourceVersion = sourceVersion;
        request.status = SubstituteStatus.WAITING;
        request.createdAt = Objects.requireNonNull(now);
        return request;
    }

    public boolean isActive() {
        return status.isActive();
    }

    /** 원본 근무 시작 시각에 도달했는지. 도달한 진행 중 요청은 수락·승인할 수 없다. */
    public boolean hasStarted(Instant now) {
        return !now.isBefore(sourceStartsAt);
    }

    public boolean isRequester(long membershipId) {
        return requesterMembershipId == membershipId;
    }

    public boolean isCandidate(long membershipId) {
        return candidateMembershipId != null && candidateMembershipId == membershipId;
    }

    /** 첫 수락: WAITING → PENDING_MANAGER, 수락자를 후보로 확정. 시작 시각 이후면 REQUEST_EXPIRED. */
    public void accept(long candidateMembershipId, Instant now) {
        requireStatus(SubstituteStatus.WAITING);
        requireNotStarted(now);
        if (isRequester(candidateMembershipId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        this.candidateMembershipId = candidateMembershipId;
        this.status = SubstituteStatus.PENDING_MANAGER;
    }

    /** 응답 가능한 지정 대상이 모두 거절: WAITING → ALL_DECLINED. */
    public void closeAllDeclined(Instant now) {
        requireStatus(SubstituteStatus.WAITING);
        close(SubstituteStatus.ALL_DECLINED, null, now);
    }

    /** 요청자 취소: WAITING·PENDING_MANAGER → CANCELLED. 요청자 확인은 호출자가 한다. */
    public void cancel(Instant now) {
        requireActive();
        close(SubstituteStatus.CANCELLED, null, now);
    }

    /** 현재 후보의 수락 철회: PENDING_MANAGER → WITHDRAWN. 후보가 아니면 FORBIDDEN. 자동 재모집 없음. */
    public void withdraw(long membershipId, Instant now) {
        requireStatus(SubstituteStatus.PENDING_MANAGER);
        if (!isCandidate(membershipId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        close(SubstituteStatus.WITHDRAWN, null, now);
    }

    /** 매니저 최종 승인: PENDING_MANAGER → APPROVED. 시작 시각 이후면 REQUEST_EXPIRED. */
    public void approve(Instant now) {
        requireStatus(SubstituteStatus.PENDING_MANAGER);
        requireNotStarted(now);
        close(SubstituteStatus.APPROVED, null, now);
    }

    /** 매니저 최종 거절: PENDING_MANAGER → REJECTED. */
    public void reject(Instant now) {
        requireStatus(SubstituteStatus.PENDING_MANAGER);
        close(SubstituteStatus.REJECTED, null, now);
    }

    /** 시작 시각 도달: 진행 중 → EXPIRED. 아직 시작 전이면 거부한다. */
    public void expire(Instant now) {
        requireActive();
        if (!hasStarted(now)) {
            throw BusinessException.withCurrentState(ErrorCode.REQUEST_STATE_CONFLICT, status.name());
        }
        close(SubstituteStatus.EXPIRED, null, now);
    }

    /** 원본 근무 삭제: 진행 중 → CANCELLED_SOURCE_CHANGED(SHIFT_DELETED). */
    public void closeBySourceDeleted(Instant now) {
        requireActive();
        close(SubstituteStatus.CANCELLED_SOURCE_CHANGED, TerminalReason.SHIFT_DELETED, now);
    }

    /** 요청자 소속 해제: 진행 중 → CANCELLED(REQUESTER_LEFT). */
    public void cancelByRequesterLeft(Instant now) {
        requireActive();
        close(SubstituteStatus.CANCELLED, TerminalReason.REQUESTER_LEFT, now);
    }

    /** 승인 대기 후보 소속 해제: PENDING_MANAGER → WITHDRAWN(CANDIDATE_LEFT). */
    public void withdrawByCandidateLeft(Instant now) {
        requireStatus(SubstituteStatus.PENDING_MANAGER);
        close(SubstituteStatus.WITHDRAWN, TerminalReason.CANDIDATE_LEFT, now);
    }

    /** 지정 대상 소속 해제로 응답 가능 대상 0명: WAITING → CANCELLED(NO_ACTIVE_TARGET). */
    public void cancelForNoActiveTarget(Instant now) {
        requireStatus(SubstituteStatus.WAITING);
        close(SubstituteStatus.CANCELLED, TerminalReason.NO_ACTIVE_TARGET, now);
    }

    private void close(SubstituteStatus terminal, TerminalReason reason, Instant now) {
        this.status = terminal;
        this.terminalReason = reason;
        this.closedAt = Objects.requireNonNull(now);
    }

    private void requireActive() {
        if (!isActive()) {
            throw conflict();
        }
    }

    private void requireStatus(SubstituteStatus expected) {
        if (status != expected) {
            throw conflict();
        }
    }

    private void requireNotStarted(Instant now) {
        if (hasStarted(now)) {
            throw BusinessException.withCurrentState(ErrorCode.REQUEST_EXPIRED, status.name());
        }
    }

    private BusinessException conflict() {
        return BusinessException.withCurrentState(ErrorCode.REQUEST_STATE_CONFLICT, status.name());
    }
}
