package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.application.MembershipGuard;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCH-07 근무 한 건 삭제(정책 B04·B17·B20·D11).
 *
 * <p>한 트랜잭션에서 근무 잠금 → 상태·버전 확인 → 소프트 삭제(version +1) → DELETED 기록 →
 * 진행 중 대타 요청 종료(B, {@link SubstituteRequestLifecycle#closeBySourceDeleted})를 함께 확정한다.
 * 어느 단계에서든 예외가 나면 전부 롤백된다. 과거 근무도 삭제할 수 있고, 반복 등록한 다른 날짜 근무는 독립 행이라 영향이 없다.
 * 잠금 순서: shift → substitute_request(B 내부). 매니저 본인 소속은 잠그지 않는다(B의 승인과 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class ShiftCommandService {

    private final MembershipGuard membershipGuard;
    private final ShiftRepository shiftRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final ShiftLocker shiftLocker;
    private final ShiftEventValues eventValues;
    private final SubstituteRequestLifecycle substituteRequestLifecycle;
    private final Clock clock;

    @Transactional
    public Deleted delete(long userId, long shiftId, long expectedVersion) {
        // store_id는 바뀌지 않으므로 잠그기 전에 읽어 권한부터 확인한다(권한 없는 사람에게 근무 상태를 알려 주지 않음)
        long storeId = shiftRepository.findById(shiftId)
                .map(Shift::getStoreId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ActiveMembership manager = membershipGuard.requireManager(userId, storeId);

        Shift shift = shiftLocker.lock(shiftId);
        ShiftConflicts.requireLive(shift, expectedVersion);

        Instant now = clock.instant();
        String before = eventValues.snapshot(shift);
        shift.delete(now);
        shiftRepository.flush();
        shiftEventRepository.saveAndFlush(ShiftEvent.record(shiftId, ShiftEventKind.DELETED, before, null,
                manager.membershipId(), null, now));

        Long closedRequestId = substituteRequestLifecycle
                .closeBySourceDeleted(shiftId, manager.membershipId())
                .orElse(null);
        return new Deleted(shiftId, closedRequestId);
    }

    /** @param closedRequestId 함께 종료한 대타 요청 id. 없으면 null */
    public record Deleted(long shiftId, Long closedRequestId) {
    }
}
