package com.baton.auth.security;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.domain.AuthSessionRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 서명·만료가 검증된 액세스 토큰을 AuthUser로 바꾼다. 로그아웃·탈퇴를 즉시 반영하기 위해
 * 세션이 살아 있고 계정이 ACTIVE인지 매 요청 DB에서 한 번의 쿼리로 확인한다.
 */
@Component
@RequiredArgsConstructor
public class SessionCheckingJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthSessionRepository authSessionRepository;
    private final Clock clock;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        long sessionId = ((Number) jwt.getClaims().get(AccessTokenService.SESSION_ID_CLAIM)).longValue();
        if (!authSessionRepository.existsUsable(sessionId, userId, clock.instant())) {
            throw new BadCredentialsException("세션이 종료되었거나 계정이 활성 상태가 아님");
        }
        return new AuthUserAuthentication(new AuthUser(userId, sessionId));
    }
}
