package com.baton.auth.security;

/**
 * 인증된 요청의 주체. 권한 판정은 이 userId와 DB의 활성 소속으로만 한다.
 *
 * @param sessionId auth_sessions.id. X-Test-User-Id(local/test 전용) 인증이면 null
 */
public record AuthUser(long userId, Long sessionId) {
}
