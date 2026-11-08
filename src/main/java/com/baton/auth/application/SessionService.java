package com.baton.auth.application;

import com.baton.auth.domain.AuthSession;
import com.baton.auth.domain.AuthSessionRepository;
import com.baton.auth.security.AuthUser;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.config.AuthProperties;
import com.baton.member.domain.AccountState;
import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 세션(auth_sessions) 발급·회전·폐기.
 */
@Service
public class SessionService {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final AccessTokenService accessTokenService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public SessionService(AuthSessionRepository authSessionRepository, UserRepository userRepository,
                          AccessTokenService accessTokenService, PlatformTransactionManager transactionManager,
                          AuthProperties properties, Clock clock) {
        this.authSessionRepository = authSessionRepository;
        this.userRepository = userRepository;
        this.accessTokenService = accessTokenService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.refreshTokenTtl = properties.refreshTokenTtl();
    }

    @Transactional
    public IssuedTokens open(long userId, OnboardingStage onboardingStage) {
        return issue(userId, onboardingStage, clock.instant());
    }

    /**
     * 회전 방식. 기존 세션을 폐기하고 새 세션을 발급한다. 이미 폐기된 토큰이 다시 쓰이면 탈취로 보고
     * 해당 사용자의 모든 세션을 폐기한다. 폐기 결과를 커밋한 뒤에 401을 던진다.
     */
    public IssuedTokens refresh(String refreshToken) {
        Optional<IssuedTokens> issued = transactionTemplate.execute(status -> rotate(refreshToken));
        return issued.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    /** 현재 세션만 종료한다. 계정과 매장 소속은 건드리지 않는다. */
    @Transactional
    public void logout(AuthUser authUser) {
        if (authUser.sessionId() != null) {
            authSessionRepository.revokeIfActive(authUser.sessionId(), clock.instant());
        }
    }

    private Optional<IssuedTokens> rotate(String refreshToken) {
        Optional<AuthSession> found = authSessionRepository.findByRefreshTokenHash(RefreshTokens.hash(refreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthSession session = found.get();
        Instant now = clock.instant();
        if (session.getRevokedAt() != null) {
            authSessionRepository.revokeAllActiveByUserId(session.getUserId(), now);
            return Optional.empty();
        }
        if (!session.isUsableAt(now)) {
            return Optional.empty();
        }
        if (authSessionRepository.revokeIfActive(session.getId(), now) == 0) {
            // 같은 토큰으로 동시에 회전 요청: 먼저 커밋한 쪽만 성공, 나머지는 재사용으로 본다
            authSessionRepository.revokeAllActiveByUserId(session.getUserId(), now);
            return Optional.empty();
        }
        return userRepository.findById(session.getUserId())
                .filter(user -> user.getAccountState() == AccountState.ACTIVE)
                .map(user -> issue(user.getId(), user.getOnboardingStage(), now));
    }

    private IssuedTokens issue(long userId, OnboardingStage onboardingStage, Instant now) {
        String refreshToken = RefreshTokens.generate();
        AuthSession session = authSessionRepository.save(
                AuthSession.open(userId, RefreshTokens.hash(refreshToken), now.plus(refreshTokenTtl), now));
        return new IssuedTokens(accessTokenService.issue(userId, session.getId()), refreshToken,
                accessTokenService.ttlSeconds(), onboardingStage);
    }
}
