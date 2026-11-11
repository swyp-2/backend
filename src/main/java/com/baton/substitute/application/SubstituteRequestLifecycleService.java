package com.baton.substitute.application;

import com.baton.integration.ActiveMembership;
import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.MembershipQuery;
import com.baton.integration.StoreManagerQuery;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.notification.application.NotificationWriter;
import com.baton.notification.domain.NotificationRepository;
import com.baton.substitute.domain.RecipientResponse;
import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.RequestRecipientRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubstituteRequestLifecycle} 구현. A의 근무 삭제·나가기·탈퇴 트랜잭션 안에서 대타 요청을 함께 정리한다.
 *
 * <p>잠금: 요청 행만 FOR UPDATE로 잠그고(여러 건이면 id 오름차순) 잠근 뒤 상태를 다시 확인한다.
 * 호출자가 먼저 잡은 잠금(근무 삭제는 shift, 소속 해제는 떠나는 membership) 다음 단계라 잠금 순서
 * (membership → shift → substitute_request)를 지킨다. 다른 사람의 소속은 잠그지 않는다
 * (두 사람이 동시에 나갈 때 서로의 소속을 잠가 교착되지 않게).
 */
@Service
@RequiredArgsConstructor
public class SubstituteRequestLifecycleService implements SubstituteRequestLifecycle {

    private final SubstituteRequestRepository requestRepository;
    private final RequestRecipientRepository recipientRepository;
    private final RequestEventRepository eventRepository;
    private final NotificationWriter notificationWriter;
    private final NotificationRepository notificationRepository;
    private final MembershipQuery membershipQuery;
    private final StoreManagerQuery storeManagerQuery;
    private final Clock clock;

    /**
     * 근무 삭제(SUB-10): 진행 중 요청을 CANCELLED_SOURCE_CHANGED(SHIFT_DELETED)로 종료한다.
     * 진행 기록 SOURCE_DELETED(행위자 = 삭제한 매니저)를 남기고 알림은 만들지 않는다(정책 이벤트 표).
     * 호출자(A)가 근무 행을 잠근 뒤 호출하므로 같은 근무의 요청 생성과 직렬화된다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> closeBySourceDeleted(long shiftId, long actorMembershipId) {
        Optional<SubstituteRequest> locked = requestRepository.findActiveIdByShiftId(shiftId)
                .flatMap(requestRepository::findByIdForUpdate)
                .filter(SubstituteRequest::isActive);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        SubstituteRequest request = locked.get();
        Instant now = now();
        SubstituteStatus before = request.getStatus();
        request.closeBySourceDeleted(now);
        eventRepository.save(RequestEvent.record(request.getId(), actorMembershipId, RequestEventKind.SOURCE_DELETED,
                before, request.getStatus(), now));
        requestRepository.flush();
        return Optional.of(request.getId());
    }

    /**
     * 소속 해제(SUB-10, 정책 D20). 호출자(A)는 떠나는 소속을 FOR UPDATE로 잠근 뒤, 소속을 해제하는 같은 트랜잭션에서 호출한다.
     * <ul>
     *   <li>요청자: 진행 중 요청 → CANCELLED(REQUESTER_LEFT)</li>
     *   <li>승인 대기 후보: → WITHDRAWN(CANDIDATE_LEFT)</li>
     *   <li>그 밖의 지정 대상: 그 대상만 UNAVAILABLE. WAITING에서 미응답 대상이 더 없으면
     *       거절한 대상이 남았으면 ALL_DECLINED(2026-11-11 결정, 거절 API 규칙과 같음), 없으면 CANCELLED(NO_ACTIVE_TARGET)</li>
     * </ul>
     * 요청이 종료되면 진행 기록 MEMBER_LEFT(행위자 = 떠나는 사람)를 남기고, 남아 있는 요청자·직전 후보·매니저에게 알린다.
     * 떠나는 사람은 알림을 받지 않는다. 지정 대상만 응답 불가가 되고 요청이 계속되면 기록·알림을 남기지 않는다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupOnMembershipLeave(long membershipId) {
        Instant now = now();
        for (Long requestId : requestRepository.findActiveIdsInvolving(membershipId)) {
            requestRepository.findByIdForUpdate(requestId)
                    .filter(SubstituteRequest::isActive)
                    .ifPresent(request -> cleanup(request, membershipId, now));
        }
        requestRepository.flush();
    }

    private void cleanup(SubstituteRequest request, long leaverId, Instant now) {
        SubstituteStatus before = request.getStatus();
        recipientRepository.findByRequestIdAndTargetMembershipId(request.getId(), leaverId)
                .ifPresent(recipient -> recipient.markUnavailable(now));
        recipientRepository.flush();

        if (request.isRequester(leaverId)) {
            request.cancelByRequesterLeft(now);
        } else if (before == SubstituteStatus.PENDING_MANAGER && request.isCandidate(leaverId)) {
            request.withdrawByCandidateLeft(now);
        } else if (before == SubstituteStatus.WAITING
                && !recipientRepository.existsByRequestIdAndResponse(request.getId(), RecipientResponse.PENDING)) {
            if (recipientRepository.existsByRequestIdAndResponseNot(request.getId(), RecipientResponse.UNAVAILABLE)) {
                request.closeAllDeclined(now);
            } else {
                request.cancelForNoActiveTarget(now);
            }
        } else {
            return;
        }

        RequestEvent event = eventRepository.save(RequestEvent.record(request.getId(), leaverId,
                RequestEventKind.MEMBER_LEFT, before, request.getStatus(), now));
        notificationWriter.notify(event, recipientsAfterLeave(request, leaverId));
    }

    /** 요청자·후보·매니저 중 지금 활성 소속인 사람(잠그지 않고 확인), 떠나는 사람 제외. */
    private Set<Long> recipientsAfterLeave(SubstituteRequest request, long leaverId) {
        Set<Long> activeCrews = new HashSet<>();
        for (ActiveMembership crew : membershipQuery.findCrewCandidates(request.getStoreId(), leaverId)) {
            activeCrews.add(crew.membershipId());
        }
        List<Long> managers = storeManagerQuery.findActiveManagers(request.getStoreId()).stream()
                .map(ActiveMembership::membershipId)
                .toList();
        return SubstituteRequestTransitionService.resultRecipients(leaverId, request.getRequesterMembershipId(),
                request.getCandidateMembershipId(), activeCrews, managers);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveRequestSummary> findActiveByShift(long shiftId, long viewerMembershipId) {
        return requestRepository.findFirstByShiftIdAndStatusIn(shiftId, SubstituteStatus.ACTIVE)
                .map(request -> new ActiveRequestSummary(request.getId(), request.getStatus().name(),
                        request.isRequester(viewerMembershipId),
                        request.getStatus() == SubstituteStatus.PENDING_MANAGER));
    }

    /**
     * 탈퇴 파기(정책 D05): 그 소속이 받은 알림을 모두 삭제한다.
     * 요청·진행 기록의 membership_id는 남긴다. A가 memberships.user_id를 비워 개인과의 연결이 끊어지기 때문이다.
     * 호출자 트랜잭션이 있으면 그 안에서 함께 확정된다.
     */
    @Override
    @Transactional
    public void anonymizeFor(long membershipId) {
        notificationRepository.deleteByRecipient(membershipId);
    }

    /** DB(timestamptz) 정밀도에 맞춰 마이크로초로 자른다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
