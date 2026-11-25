package com.baton.shift.application;

import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 계정 탈퇴와 같은 트랜잭션에서 본인의 미종료 근무를 삭제하고 관련 요청을 종료한다. */
@Service
@RequiredArgsConstructor
public class ShiftWithdrawalService {

    private final ShiftRepository shiftRepository;
    private final ShiftLocker shiftLocker;
    private final ShiftEventRepository shiftEventRepository;
    private final ShiftEventValues eventValues;
    private final SubstituteRequestLifecycle substituteRequestLifecycle;

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteUnfinished(long membershipId, Instant now) {
        for (Long shiftId : shiftRepository.findUnfinishedIdsByAssignee(membershipId, now)) {
            Shift shift = shiftLocker.lock(shiftId);
            // 매니저의 삭제 또는 대타 승인과 경합했다면 잠금 후 최신 상태를 따른다.
            if (shift.isDeleted() || shift.getAssigneeMembershipId() != membershipId
                    || !shift.getEndsAt().isAfter(now)) {
                continue;
            }
            String before = eventValues.snapshot(shift);
            shift.delete(now);
            shiftRepository.flush();
            shiftEventRepository.saveAndFlush(ShiftEvent.record(shiftId, ShiftEventKind.DELETED, before, null,
                    membershipId, null, now));
            substituteRequestLifecycle.closeBySourceDeleted(shiftId, membershipId);
        }
    }
}
