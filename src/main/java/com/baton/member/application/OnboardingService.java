package com.baton.member.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CO-04 이름, CO-05 역할. 둘 다 매장 소속·매장 권한을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OnboardingService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final MeService meService;

    public MeView setName(long userId, String rawName) {
        User user = lock(userId);
        user.setName(rawName);
        return meService.assemble(user);
    }

    public MeView selectRole(long userId, String rawRole) {
        User user = lock(userId);
        // 가입 후 역할 변경은 범위 밖. 기존 매장 권한 승격 경로가 되지 않도록 활성 소속이 있으면 거부한다.
        if (membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).isPresent()) {
            throw BusinessException.withCurrentState(ErrorCode.ONBOARDING_STAGE_CONFLICT,
                    user.getOnboardingStage().name());
        }
        user.selectRole(parseRole(rawRole));
        return meService.assemble(user);
    }

    private User lock(long userId) {
        return userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private static Role parseRole(String rawRole) {
        if (rawRole != null) {
            for (Role role : Role.values()) {
                if (role.name().equals(rawRole)) {
                    return role;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                List.of(new FieldError("role", "MANAGER 또는 CREW 중 하나를 선택해 주세요.")));
    }
}
