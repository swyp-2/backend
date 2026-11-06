package com.baton.auth.application.social;

import com.baton.auth.domain.SocialProvider;

/**
 * 검증된 소셜 ID 토큰에서 쓰는 값. 이메일 등 다른 claim은 꺼내지 않는다.
 */
public record VerifiedSocialIdentity(SocialProvider provider, String subject) {
}
