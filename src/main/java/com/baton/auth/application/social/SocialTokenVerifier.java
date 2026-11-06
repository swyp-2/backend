package com.baton.auth.application.social;

import com.baton.auth.domain.SocialProvider;

/**
 * 소셜 ID 토큰 검증. 테스트에서는 가짜 구현(@Primary)으로 교체한다.
 */
public interface SocialTokenVerifier {

    /**
     * @throws com.baton.common.BusinessException 서명·iss·aud·exp 검증 실패면 UNAUTHENTICATED,
     *                                            제공자 키 조회 실패면 INTERNAL_ERROR
     */
    VerifiedSocialIdentity verify(SocialProvider provider, String idToken);
}
