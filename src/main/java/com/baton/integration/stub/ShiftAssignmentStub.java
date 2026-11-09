package com.baton.integration.stub;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.ShiftSnapshot;
import com.baton.shift.domain.Shift;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A의 {@link ShiftAssignment} 구현이 들어오기 전까지 쓰는 임시 구현(B 작성). A 구현 빈이 생기면
 * {@link IntegrationStubConfig}의 조건 때문에 자동으로 등록되지 않는다.
 *
 * <p>A의 {@link Shift} 엔티티를 읽고 그 공개 메서드만 호출하며 A 코드는 수정하지 않는다.
 * 실제 구현과의 차이: 근무 처리 기록(shift_events, REASSIGNED)을 남기지 않는다.
 */
public class ShiftAssignmentStub implements ShiftAssignment {

    private final EntityManager entityManager;

    public ShiftAssignmentStub(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reassign(long shiftId, long fromMembershipId, long toMembershipId, long expectedVersion) {
        Shift shift = entityManager.find(Shift.class, shiftId, LockModeType.PESSIMISTIC_WRITE);
        if (shift == null || shift.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.SHIFT_STATE_CONFLICT);
        }
        // 삭제·담당자 불일치·같은 사람 지정은 Shift.reassign이 SHIFT_STATE_CONFLICT로 거부한다
        shift.reassign(fromMembershipId, toMembershipId);
        // 호출자가 같은 트랜잭션에서 바뀐 version을 볼 수 있게 즉시 반영한다
        entityManager.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftSnapshot getSnapshot(long shiftId) {
        Shift shift = entityManager.find(Shift.class, shiftId);
        if (shift == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new ShiftSnapshot(shift.getId(), shift.getStoreId(), shift.getAssigneeMembershipId(),
                shift.getStartsAt(), shift.getEndsAt(), shift.getVersion(), shift.isDeleted());
    }
}
