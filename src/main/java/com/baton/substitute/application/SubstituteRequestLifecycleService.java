package com.baton.substitute.application;

import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubstituteRequestLifecycle} 구현.
 *
 * <p>대타 요청 기능이 아직 없어 처리할 요청이 항상 0건인 상태의 동작을 한다
 * (종료·정리할 것 없음, 조회 결과 없음). 대타 요청 구현 시 이 클래스를 채운다.
 */
@Service
public class SubstituteRequestLifecycleService implements SubstituteRequestLifecycle {

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> closeBySourceDeleted(long shiftId, long actorMembershipId) {
        return Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanupOnMembershipLeave(long membershipId) {
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveRequestSummary> findActiveByShift(long shiftId, long viewerMembershipId) {
        return Optional.empty();
    }

    @Override
    @Transactional
    public void anonymizeFor(long membershipId) {
    }
}
