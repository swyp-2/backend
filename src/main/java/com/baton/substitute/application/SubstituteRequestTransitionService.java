package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.StoreManagerQuery;
import com.baton.notification.application.NotificationWriter;
import com.baton.substitute.domain.RecipientResponse;
import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.RequestParties;
import com.baton.substitute.domain.RequestRecipient;
import com.baton.substitute.domain.RequestRecipientRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대타 요청 상태 전이(SUB-06~09, APR-01~02). 권한은 인증된 userId와 DB의 활성 소속·요청 관계로만 판정한다.
 *
 * <p>모든 전이의 공통 흐름(잠금 순서: idempotency_records(호출자) → membership → shift → substitute_request):
 * <ol>
 *   <li>잠그지 않은 관계자 스냅샷으로 매장을 찾고 호출자의 활성 소속을 확인한다</li>
 *   <li>요청자·후보·행위 크루원 소속을 id 오름차순으로 잠근다. 나가기·탈퇴와 직렬화되고, 알림 수신 가능 여부도 이것으로 정한다</li>
 *   <li>(승인만) 근무 행을 잠근다</li>
 *   <li>요청 행을 잠그고 상태·관계·시작 시각을 다시 확인한 뒤 바꾼다. 처리 기록과 알림을 같은 트랜잭션에 남긴다</li>
 * </ol>
 * 근무 시작 시각 이후에는 6개 행동 모두 REQUEST_EXPIRED(2026-11-10 결정). 요청 본문의 버전은 받지 않는다(같은 날 결정).
 */
@Service
@RequiredArgsConstructor
public class SubstituteRequestTransitionService {

    static final String IDENTICAL_SHIFT_INDEX = "shifts_identical_live_uk";
    static final String CANDIDATE_HAS_SAME_SHIFT = "후보에게 이미 같은 시간의 근무가 있어요. 근무표를 확인해 주세요.";
    private static final String MANAGER = "MANAGER";
    private static final String CREW = "CREW";

    private final SubstituteRequestRepository requestRepository;
    private final RequestRecipientRepository recipientRepository;
    private final RequestEventRepository eventRepository;
    private final NotificationWriter notificationWriter;
    private final MembershipQuery membershipQuery;
    private final StoreManagerQuery storeManagerQuery;
    private final ShiftAssignment shiftAssignment;
    private final Clock clock;

    /** SUB-06: 지정 크루원의 첫 수락(거절했던 대상의 재수락 포함). WAITING → PENDING_MANAGER. 담당자는 그대로. */
    @Transactional
    public void accept(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, false);
        SubstituteRequest request = ctx.request();
        RequestRecipient recipient = requireRecipient(ctx);
        requireOpen(request, ctx.now());
        if (request.getStatus() != SubstituteStatus.WAITING || !recipient.canAccept()) {
            throw conflict(request);
        }
        SubstituteStatus before = request.getStatus();
        request.accept(ctx.actor().membershipId(), ctx.now());
        recipient.accept(ctx.now());
        record(ctx, RequestEventKind.CANDIDATE_ACCEPTED, before);
    }

    /** SUB-07: 미응답 지정 크루원의 거절. 남은 미응답 대상이 없으면 ALL_DECLINED로 종료한다. */
    @Transactional
    public void reject(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, false);
        SubstituteRequest request = ctx.request();
        RequestRecipient recipient = requireRecipient(ctx);
        requireOpen(request, ctx.now());
        if (request.getStatus() != SubstituteStatus.WAITING || !recipient.canDecline()) {
            throw conflict(request);
        }
        SubstituteStatus before = request.getStatus();
        recipient.decline(ctx.now());
        recipientRepository.flush();
        if (!recipientRepository.existsByRequestIdAndResponse(request.getId(), RecipientResponse.PENDING)) {
            request.closeAllDeclined(ctx.now());
        }
        record(ctx, RequestEventKind.TARGET_DECLINED, before);
    }

    /** SUB-08: 요청자 취소. WAITING·PENDING_MANAGER → CANCELLED. */
    @Transactional
    public void cancel(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, false);
        SubstituteRequest request = ctx.request();
        if (!request.isRequester(ctx.actor().membershipId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireOpen(request, ctx.now());
        SubstituteStatus before = request.getStatus();
        request.cancel(ctx.now());
        record(ctx, RequestEventKind.REQUEST_CANCELLED, before);
    }

    /** SUB-09: 현재 후보의 수락 철회. PENDING_MANAGER → WITHDRAWN. 자동 재모집 없음. */
    @Transactional
    public void withdraw(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, false);
        SubstituteRequest request = ctx.request();
        if (!request.isCandidate(ctx.actor().membershipId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireOpen(request, ctx.now());
        SubstituteStatus before = request.getStatus();
        request.withdraw(ctx.actor().membershipId(), ctx.now());
        record(ctx, RequestEventKind.CANDIDATE_WITHDRAWN, before);
    }

    /**
     * APR-01: 매니저 최종 승인. PENDING_MANAGER → APPROVED, 근무 담당자를 요청자에서 후보로 바꾼다.
     * 후보가 소속을 잃었으면 REQUEST_STATE_CONFLICT, 근무가 삭제·변경됐으면 SHIFT_STATE_CONFLICT.
     */
    @Transactional
    public void approve(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, true);
        SubstituteRequest request = ctx.request();
        requireManager(ctx);
        requireOpen(request, ctx.now());
        if (request.getStatus() != SubstituteStatus.PENDING_MANAGER
                || !ctx.activeParties().contains(request.getCandidateMembershipId())) {
            throw conflict(request);
        }
        SubstituteStatus before = request.getStatus();
        reassign(request);
        request.approve(ctx.now());
        record(ctx, RequestEventKind.MANAGER_APPROVED, before);
    }

    /** APR-02: 매니저 최종 거절. PENDING_MANAGER → REJECTED. 담당자는 그대로. */
    @Transactional
    public void finalReject(long userId, long requestId) {
        Locked ctx = lock(userId, requestId, false);
        SubstituteRequest request = ctx.request();
        requireManager(ctx);
        requireOpen(request, ctx.now());
        SubstituteStatus before = request.getStatus();
        request.reject(ctx.now());
        record(ctx, RequestEventKind.MANAGER_REJECTED, before);
    }

    /**
     * 처리 결과 알림 수신자(정책 N05·7절): 요청자 + 후보(현재 또는 사건 직전) + 소속 매니저.
     * 지금 활성 소속인 사람만, 같은 사람은 한 번만. 후보가 없으면 후보 수신자도 없다(다른 지정 대상으로 넓히지 않음).
     * 행동한 본인은 받지 않는다(2026-11-11 결정: 알림 목록 없이 빨간 점만 쓰므로 본인 행동으로 점을 켜지 않는다.
     * 기능명세서 NTF-04 "행위자 본인도 포함"과 다름). 매니저가 여럿이면 처리한 매니저만 빠진다.
     *
     * @param actorMembershipId 이 사건을 일으킨 사람
     * @param activeParties 잠금 조회로 활성이 확인된 요청자·후보 소속 id
     */
    static Set<Long> resultRecipients(long actorMembershipId, long requesterMembershipId,
                                      Long candidateMembershipId, Collection<Long> activeParties,
                                      Collection<Long> activeManagerIds) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (activeParties.contains(requesterMembershipId)) {
            recipients.add(requesterMembershipId);
        }
        if (candidateMembershipId != null && activeParties.contains(candidateMembershipId)) {
            recipients.add(candidateMembershipId);
        }
        recipients.addAll(activeManagerIds);
        recipients.remove(actorMembershipId);
        return recipients;
    }

    // ------------------------------------------------------------------------------------------------

    private Locked lock(long userId, long requestId, boolean lockShift) {
        Instant now = now();
        RequestParties parties = requestRepository.findPartiesById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ActiveMembership actor = membershipQuery.requireActive(userId, parties.storeId());

        TreeSet<Long> toLock = new TreeSet<>();
        toLock.add(parties.requesterMembershipId());
        if (parties.candidateMembershipId() != null) {
            toLock.add(parties.candidateMembershipId());
        }
        if (CREW.equals(actor.role())) {
            toLock.add(actor.membershipId());
        }
        Set<Long> active = new HashSet<>();
        for (Long membershipId : toLock) {
            membershipQuery.lockActiveById(membershipId)
                    .filter(m -> m.storeId() == parties.storeId())
                    .ifPresent(m -> active.add(m.membershipId()));
        }
        if (CREW.equals(actor.role()) && !active.contains(actor.membershipId())) {
            // 확인 직후 나가기·탈퇴가 먼저 확정됨
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (lockShift) {
            shiftAssignment.lockSnapshot(parties.shiftId());
        }

        SubstituteRequest request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!Objects.equals(request.getCandidateMembershipId(), parties.candidateMembershipId())) {
            // 잠그는 사이 다른 사람이 수락함. 새 후보 소속은 잠그지 않았으므로 최신 상태로 다시 시도하게 한다
            throw conflict(request);
        }
        return new Locked(request, actor, active, now);
    }

    private RequestRecipient requireRecipient(Locked ctx) {
        return recipientRepository
                .findByRequestIdAndTargetMembershipId(ctx.request().getId(), ctx.actor().membershipId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    private static void requireManager(Locked ctx) {
        if (!MANAGER.equals(ctx.actor().role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 종료된 요청이면 REQUEST_STATE_CONFLICT, 시작 시각에 도달했으면 REQUEST_EXPIRED(스케줄러 반영 전이라도). */
    private static void requireOpen(SubstituteRequest request, Instant now) {
        if (!request.isActive()) {
            throw conflict(request);
        }
        if (request.hasStarted(now)) {
            throw BusinessException.withCurrentState(ErrorCode.REQUEST_EXPIRED, request.getStatus().name());
        }
    }

    /** 후보에게 완전히 같은 근무가 이미 있으면 부분 유니크 위반 → SHIFT_STATE_CONFLICT(2026-11-10 결정). */
    private void reassign(SubstituteRequest request) {
        try {
            shiftAssignment.reassign(request.getShiftId(), request.getRequesterMembershipId(),
                    request.getCandidateMembershipId(), request.getSourceVersion());
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            if (mentions(e, IDENTICAL_SHIFT_INDEX)) {
                throw new BusinessException(ErrorCode.SHIFT_STATE_CONFLICT, CANDIDATE_HAS_SAME_SHIFT);
            }
            throw e;
        }
    }

    private void record(Locked ctx, RequestEventKind kind, SubstituteStatus before) {
        SubstituteRequest request = ctx.request();
        RequestEvent event = eventRepository.save(RequestEvent.record(request.getId(), ctx.actor().membershipId(),
                kind, before, request.getStatus(), ctx.now()));
        List<Long> managers = storeManagerQuery.findActiveManagers(request.getStoreId()).stream()
                .map(ActiveMembership::membershipId)
                .toList();
        notificationWriter.notify(event, resultRecipients(ctx.actor().membershipId(),
                request.getRequesterMembershipId(), request.getCandidateMembershipId(), ctx.activeParties(), managers));
        requestRepository.flush();
    }

    private static boolean mentions(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException conflict(SubstituteRequest request) {
        return BusinessException.withCurrentState(ErrorCode.REQUEST_STATE_CONFLICT, request.getStatus().name());
    }

    /** DB(timestamptz) 정밀도에 맞춰 마이크로초로 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Locked(SubstituteRequest request, ActiveMembership actor, Set<Long> activeParties, Instant now) {
    }
}
