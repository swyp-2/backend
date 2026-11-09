package com.baton.substitute.application;

import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubstituteRequestLifecycle} 구현.
 *
 * <p>findActiveByShift는 구현됨. 종료·정리·파기는 상태 전이 API 작업에서 채운다(지금은 아무것도 하지 않음).
 */
@Service
@RequiredArgsConstructor
public class SubstituteRequestLifecycleService implements SubstituteRequestLifecycle {

    private final SubstituteRequestRepository requestRepository;

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
        return requestRepository.findFirstByShiftIdAndStatusIn(shiftId, SubstituteStatus.ACTIVE)
                .map(request -> new ActiveRequestSummary(request.getId(), request.getStatus().name(),
                        request.isRequester(viewerMembershipId),
                        request.getStatus() == SubstituteStatus.PENDING_MANAGER));
    }

    @Override
    @Transactional
    public void anonymizeFor(long membershipId) {
    }
}
