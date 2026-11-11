package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.ShiftSnapshot;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ShiftAssignment} 실제 구현(A). 이 빈이 있으면 integration.stub의 임시 구현은 등록되지 않는다.
 *
 * <p>근무 담당자를 바꾸는 경로는 대타 승인(APR-01·B16)의 {@link #reassign} 하나뿐이다(근무 수정 API 없음).
 */
@Service
@RequiredArgsConstructor
public class ShiftAssignmentService implements ShiftAssignment {

    private final ShiftRepository shiftRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final ShiftLocker shiftLocker;
    private final ShiftEventValues eventValues;
    private final MembershipQuery membershipQuery;
    private final Clock clock;

    /**
     * 잠금 순서(membership → shift)를 지키려고 새 담당자 소속을 근무보다 먼저 잠근다.
     * 호출자(B)가 같은 트랜잭션에서 이미 잠갔다면 다시 잠가도 바로 통과한다.
     * shifts_identical_live_uk 위반(새 담당자에게 완전히 같은 근무가 있음)은 감싸지 않고 그대로 던진다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reassign(long shiftId, long fromMembershipId, long toMembershipId, long expectedVersion) {
        Optional<ActiveMembership> to = membershipQuery.lockActiveById(toMembershipId);
        Shift shift = shiftLocker.lock(shiftId);
        ShiftConflicts.requireLive(shift, expectedVersion);
        if (shift.getAssigneeMembershipId() != fromMembershipId || toMembershipId == fromMembershipId
                || to.isEmpty() || to.get().storeId() != shift.getStoreId()) {
            throw ShiftConflicts.changed();
        }

        shift.reassign(fromMembershipId, toMembershipId);
        // 호출자가 같은 트랜잭션에서 바뀐 version을 보고, 유니크 위반도 여기서 드러나도록 즉시 반영한다
        shiftRepository.flush();
        // request_id·행위자(승인 매니저)는 인터페이스로 전달되지 않아 비워 둔다(B와 합의 필요)
        shiftEventRepository.saveAndFlush(ShiftEvent.record(shiftId, ShiftEventKind.REASSIGNED,
                eventValues.assignee(fromMembershipId), eventValues.assignee(toMembershipId), null, null,
                clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftSnapshot getSnapshot(long shiftId) {
        return shiftRepository.findById(shiftId)
                .map(ShiftAssignmentService::toSnapshot)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ShiftSnapshot lockSnapshot(long shiftId) {
        return toSnapshot(shiftLocker.lock(shiftId));
    }

    private static ShiftSnapshot toSnapshot(Shift shift) {
        return new ShiftSnapshot(shift.getId(), shift.getStoreId(), shift.getAssigneeMembershipId(),
                shift.getStartsAt(), shift.getEndsAt(), shift.getVersion(), shift.isDeleted());
    }
}
