package com.baton.auth.application;

import com.baton.auth.application.social.SocialTokenVerifier;
import com.baton.auth.application.social.VerifiedSocialIdentity;
import com.baton.auth.domain.SocialProvider;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.member.domain.AccountState;
import com.baton.member.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 소셜 ID 토큰 검증 → 회원 조회/생성 → 세션 발급. 외부 키 조회 중에 DB 트랜잭션을 잡지 않도록
 * 이 메서드 자체는 트랜잭션이 없다.
 */
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialTokenVerifier socialTokenVerifier;
    private final SocialSignUp socialSignUp;
    private final SessionService sessionService;

    public IssuedTokens complete(SocialProvider provider, String idToken) {
        VerifiedSocialIdentity identity = socialTokenVerifier.verify(provider, idToken);
        User user = findOrCreate(identity);
        if (user.getAccountState() != AccountState.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return sessionService.open(user.getId(), user.getOnboardingStage());
    }

    private User findOrCreate(VerifiedSocialIdentity identity) {
        try {
            return socialSignUp.findOrCreate(identity);
        } catch (DataIntegrityViolationException e) {
            // 같은 소셜 계정의 동시 첫 로그인: 먼저 커밋된 회원으로 처리한다
            return socialSignUp.findExisting(identity).orElseThrow(() -> e);
        }
    }
}
