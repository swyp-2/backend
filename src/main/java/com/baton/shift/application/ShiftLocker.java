package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.shift.domain.Shift;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근무 행 비관적 잠금(SELECT ... FOR UPDATE). 잠금 순서: membership → shift → substitute_request.
 *
 * <p>같은 트랜잭션에서 이미 읽은 엔티티가 있어도 {@code refresh}로 잠금과 함께 최신 값을 다시 읽는다.
 * (find에 잠금 모드만 주면 이미 관리 중인 엔티티의 필드가 갱신되지 않을 수 있다)
 */
@Component
@RequiredArgsConstructor
class ShiftLocker {

    private final EntityManager entityManager;

    /** 없는 근무면 NOT_FOUND. 삭제된 근무도 잠가서 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Shift lock(long shiftId) {
        Shift shift = entityManager.find(Shift.class, shiftId);
        if (shift == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        entityManager.refresh(shift, LockModeType.PESSIMISTIC_WRITE);
        return shift;
    }
}
