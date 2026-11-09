package com.baton.auth.application;

import com.baton.auth.domain.AuthSession;
import com.baton.auth.domain.AuthSessionRepository;
import com.baton.auth.security.AuthUser;
import com.baton.member.domain.OnboardingStage;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 세션(auth_sessions) 발급·폐기. 세션 수명은 액세스 토큰 수명과 같고 사용해도 연장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final AuthSessionRepository authSessionRepository;
    private final AccessTokenService accessTokenService;
    private final Clock clock;

    @Transactional
    public IssuedTokens open(long userId, OnboardingStage onboardingStage) {
        // JWT exp는 초 단위라 세션 expires_at과 정확히 같은 시각이 되도록 초로 자른다
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        AuthSession session = authSessionRepository.save(
                AuthSession.open(userId, accessTokenService.expiresAt(now), now));
        return new IssuedTokens(accessTokenService.issue(userId, session.getId(), now),
                accessTokenService.ttlSeconds(), onboardingStage);
    }

    /** 현재 세션만 종료한다. 계정과 매장 소속은 건드리지 않는다. */
    @Transactional
    public void logout(AuthUser authUser) {
        if (authUser.sessionId() != null) {
            authSessionRepository.revokeIfActive(authUser.sessionId(), clock.instant());
        }
    }
}
