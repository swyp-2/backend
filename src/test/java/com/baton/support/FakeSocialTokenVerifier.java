package com.baton.support;

import com.baton.auth.application.social.SocialTokenVerifier;
import com.baton.auth.application.social.VerifiedSocialIdentity;
import com.baton.auth.domain.SocialProvider;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

/**
 * 통합 테스트용 소셜 검증기. "valid:{sub}" 형식만 통과시키고 나머지는 UNAUTHENTICATED.
 */
public class FakeSocialTokenVerifier implements SocialTokenVerifier {

    private static final String PREFIX = "valid:";

    public static String idTokenFor(String subject) {
        return PREFIX + subject;
    }

    @Override
    public VerifiedSocialIdentity verify(SocialProvider provider, String idToken) {
        if (!idToken.startsWith(PREFIX) || idToken.length() == PREFIX.length()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return new VerifiedSocialIdentity(provider, idToken.substring(PREFIX.length()));
    }
}
