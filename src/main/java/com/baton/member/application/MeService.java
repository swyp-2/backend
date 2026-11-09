package com.baton.member.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final StoreRepository storeRepository;

    public MeView getMe(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        return assemble(user);
    }

    /** 호출자 트랜잭션 안에서 갱신된 User로 응답을 만든다. */
    MeView assemble(User user) {
        MeView.MembershipView membership = membershipRepository
                .findByUserIdAndState(user.getId(), MembershipState.ACTIVE)
                .map(this::toView)
                .orElse(null);
        return new MeView(
                user.getId(),
                user.getDisplayName(),
                user.getOnboardingStage(),
                user.getSelectedRole(),
                membership,
                NextScreen.of(membership != null, user.getOnboardingStage(), user.getSelectedRole()));
    }

    private MeView.MembershipView toView(Membership membership) {
        Store store = storeRepository.findById(membership.getStoreId())
                .orElseThrow(() -> new IllegalStateException("소속 매장 없음: storeId=" + membership.getStoreId()));
        return new MeView.MembershipView(membership.getId(), store.getId(), store.getName(), membership.getRole(),
                membership.getPersonalColor());
    }
}
