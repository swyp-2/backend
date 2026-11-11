package com.baton.store.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.StoreManagerQuery;
import com.baton.member.domain.AccountState;
import com.baton.member.domain.Role;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 사용자의 매장 소속·역할 판정. 권한의 근거는 DB의 활성 소속뿐이다.
 * B가 쓰는 {@link MembershipQuery}·{@link StoreManagerQuery}의 실제 구현이다(이 빈이 있으면 integration.stub의 임시 구현은 빠진다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipGuard implements MembershipQuery, StoreManagerQuery {

    public static final String WITHDRAWN_MEMBER_NAME = "탈퇴한 구성원";

    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    @Override
    public ActiveMembership requireActive(long userId, long storeId) {
        return membershipRepository.findByStoreIdAndUserIdAndState(storeId, userId, MembershipState.ACTIVE)
                .map(MembershipGuard::toActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    public ActiveMembership requireManager(long userId, long storeId) {
        ActiveMembership membership = requireActive(userId, storeId);
        if (!Role.MANAGER.name().equals(membership.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return membership;
    }

    /** MVP는 계정당 활성 매장이 하나다(memberships_user_active_uk). */
    public Optional<ActiveMembership> findActiveByUser(long userId) {
        return membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE)
                .map(MembershipGuard::toActive);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ActiveMembership> lockActiveById(long membershipId) {
        return membershipRepository.findActiveByIdForUpdate(membershipId).map(MembershipGuard::toActive);
    }

    @Override
    public List<ActiveMembership> findCrewCandidates(long storeId, long excludeMembershipId) {
        return membershipRepository.findByStoreIdAndStateAndRoleAndIdNotOrderByIdAsc(
                        storeId, MembershipState.ACTIVE, Role.CREW, excludeMembershipId).stream()
                .map(MembershipGuard::toActive)
                .toList();
    }

    /** 대타 결과 알림의 "소속 매니저" 수신자. 매장의 ACTIVE MANAGER, id 오름차순. 잠그지 않는다. */
    @Override
    public List<ActiveMembership> findActiveManagers(long storeId) {
        return membershipRepository.findByStoreIdAndStateAndRoleOrderByIdAsc(
                        storeId, MembershipState.ACTIVE, Role.MANAGER).stream()
                .map(MembershipGuard::toActive)
                .toList();
    }

    @Override
    public String displayNameOf(long membershipId) {
        Membership membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (membership.getUserId() == null) {
            return WITHDRAWN_MEMBER_NAME;
        }
        return userRepository.findById(membership.getUserId())
                .filter(user -> user.getAccountState() == AccountState.ACTIVE)
                .map(user -> user.getDisplayName())
                .orElse(WITHDRAWN_MEMBER_NAME);
    }

    private static ActiveMembership toActive(Membership membership) {
        return new ActiveMembership(membership.getId(), membership.getStoreId(), membership.getUserId(),
                membership.getRole().name());
    }
}
