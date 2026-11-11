package com.baton.integration.stub;

import com.baton.integration.ActiveRequestSummary;
import com.baton.integration.SubstituteRequestLifecycle;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * B의 {@link SubstituteRequestLifecycle} 구현이 없을 때만 쓰는 빈 구현(A 작성).
 * {@link IntegrationStubConfig}에서 조건부로 등록하며, B 구현 빈이 있으면 등록되지 않는다.
 * 대타 요청이 없다고 보고 아무것도 바꾸지 않는다.
 */
public class NoopSubstituteRequestLifecycle implements SubstituteRequestLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NoopSubstituteRequestLifecycle.class);

    @Override
    public Optional<Long> closeBySourceDeleted(long shiftId, long actorMembershipId) {
        return Optional.empty();
    }

    @Override
    public void cleanupOnMembershipLeave(long membershipId) {
        log.info("[stub] cleanupOnMembershipLeave 건너뜀: membershipId={}", membershipId);
    }

    @Override
    public Optional<ActiveRequestSummary> findActiveByShift(long shiftId, long viewerMembershipId) {
        return Optional.empty();
    }

    @Override
    public void anonymizeFor(long membershipId) {
        log.info("[stub] anonymizeFor 건너뜀: membershipId={}", membershipId);
    }
}
