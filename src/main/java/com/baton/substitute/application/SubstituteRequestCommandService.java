package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.ShiftSnapshot;
import com.baton.notification.application.NotificationWriter;
import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.RequestRecipient;
import com.baton.substitute.domain.RequestRecipientRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대타 요청 변경(SUB-02 생성). 권한은 인증된 userId와 DB의 활성 소속으로만 판정한다.
 *
 * <p>잠금 순서: idempotency_records(호출자) → membership(요청자·대상, id 오름차순) → shift → substitute_request.
 */
@Service
@RequiredArgsConstructor
public class SubstituteRequestCommandService {

    static final String TARGETS_FIELD = "target_membership_ids";
    private static final String CREW = "CREW";
    private static final String ACTIVE_REQUEST_INDEX = "substitute_requests_shift_active_uk";

    private final SubstituteRequestRepository requestRepository;
    private final RequestRecipientRepository recipientRepository;
    private final RequestEventRepository eventRepository;
    private final NotificationWriter notificationWriter;
    private final MembershipQuery membershipQuery;
    private final ShiftAssignment shiftAssignment;
    private final Clock clock;

    /**
     * 본인 담당·시작 전 근무에 대타 요청을 만들고 지정 대상에게 새 요청 알림을 보낸다. 만든 요청 id를 돌려준다.
     */
    @Transactional
    public long create(long userId, long shiftId, List<Long> targetMembershipIds) {
        Instant now = now();

        // 1) 근무가 속한 매장에서 요청자 확인(잠금 전 사전 검사)
        ShiftSnapshot peek = shiftAssignment.getSnapshot(shiftId);
        ActiveMembership requester = membershipQuery.requireActive(userId, peek.storeId());
        if (!CREW.equals(requester.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Set<Long> targets = validateTargets(requester, targetMembershipIds);

        // 2) 소속 잠금(id 오름차순). 동시에 나가기·탈퇴가 진행되면 그쪽이 끝난 뒤 다시 확인한다
        lockMemberships(requester.membershipId(), targets);

        // 3) 근무 잠금 후 재검증. 동시 삭제·같은 근무 동시 생성은 여기서 순서가 정해진다
        ShiftSnapshot shift = shiftAssignment.lockSnapshot(shiftId);
        if (shift.assigneeMembershipId() != requester.membershipId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (shift.deleted()) {
            throw new BusinessException(ErrorCode.SHIFT_STATE_CONFLICT);
        }
        if (!now.isBefore(shift.startsAt())) {
            throw new BusinessException(ErrorCode.REQUEST_EXPIRED);
        }
        Optional<SubstituteRequest> existing =
                requestRepository.findFirstByShiftIdAndStatusIn(shiftId, SubstituteStatus.ACTIVE);
        if (existing.isPresent()) {
            throw BusinessException.activeRequestExists(existing.get().getId());
        }

        // 4) 저장: 요청 → 지정 대상 → 생성 이벤트 → 대상자 알림
        SubstituteRequest request = saveRequest(SubstituteRequest.open(shift.storeId(), shiftId,
                requester.membershipId(), shift.startsAt(), shift.endsAt(), shift.version(), now));
        recipientRepository.saveAll(targets.stream()
                .map(target -> RequestRecipient.designate(request.getId(), target))
                .toList());
        RequestEvent event = eventRepository.save(RequestEvent.record(request.getId(), requester.membershipId(),
                RequestEventKind.REQUEST_CREATED, null, SubstituteStatus.WAITING, now));
        notificationWriter.notify(event, targets);
        return request.getId();
    }

    /** 1명 이상, 중복 없음, 모두 같은 매장의 활성 크루원이고 본인이 아님. 순서는 요청 본문 순서를 유지한다. */
    private Set<Long> validateTargets(ActiveMembership requester, List<Long> targetMembershipIds) {
        if (targetMembershipIds == null || targetMembershipIds.isEmpty()) {
            throw invalidTargets("요청할 동료를 한 명 이상 선택해 주세요.");
        }
        Set<Long> targets = new LinkedHashSet<>();
        for (Long target : targetMembershipIds) {
            if (target == null || !targets.add(target)) {
                throw invalidTargets("같은 동료를 중복해서 보낼 수 없어요.");
            }
        }
        Set<Long> candidates = new HashSet<>();
        for (ActiveMembership candidate : membershipQuery.findCrewCandidates(requester.storeId(),
                requester.membershipId())) {
            candidates.add(candidate.membershipId());
        }
        if (!candidates.containsAll(targets)) {
            throw invalidTargets("요청할 수 없는 동료가 포함되어 있어요. 동료 목록을 다시 불러와 주세요.");
        }
        return targets;
    }

    private void lockMemberships(long requesterMembershipId, Set<Long> targets) {
        TreeSet<Long> ordered = new TreeSet<>(targets);
        ordered.add(requesterMembershipId);
        for (Long membershipId : ordered) {
            Optional<ActiveMembership> locked = membershipQuery.lockActiveById(membershipId);
            if (locked.isEmpty()) {
                if (membershipId == requesterMembershipId) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                throw invalidTargets("요청할 수 없는 동료가 포함되어 있어요. 동료 목록을 다시 불러와 주세요.");
            }
        }
    }

    private SubstituteRequest saveRequest(SubstituteRequest request) {
        try {
            return requestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            // 근무 잠금으로 순서가 정해져 보통은 오지 않지만, 마지막 방어선으로 부분 유니크 위반을 도메인 에러로 바꾼다
            if (e.getMostSpecificCause().getMessage() != null
                    && e.getMostSpecificCause().getMessage().contains(ACTIVE_REQUEST_INDEX)) {
                throw BusinessException.activeRequestExists(null);
            }
            throw e;
        }
    }

    private static BusinessException invalidTargets(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(TARGETS_FIELD, message)));
    }

    /** DB(timestamptz) 정밀도에 맞춰 마이크로초로 자른다. 응답 시각과 목록 커서가 저장값과 같아진다. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
