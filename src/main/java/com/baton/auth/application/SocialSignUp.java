package com.baton.auth.application;

import com.baton.auth.application.social.VerifiedSocialIdentity;
import com.baton.auth.domain.SocialAccount;
import com.baton.auth.domain.SocialAccountRepository;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * (provider, sub)로 회원을 찾거나 새로 만든다. 메서드마다 독립 트랜잭션이어야
 * 유니크 충돌로 롤백된 뒤 새 트랜잭션에서 재조회할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class SocialSignUp {

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 동시 가입 경합에서 지면 DataIntegrityViolationException(social_accounts_provider_subject_uk). */
    @Transactional
    public User findOrCreate(VerifiedSocialIdentity identity) {
        return find(identity).orElseGet(() -> {
            User user = userRepository.saveAndFlush(User.signUp());
            socialAccountRepository.saveAndFlush(
                    SocialAccount.link(user.getId(), identity.provider(), identity.subject(), clock.instant()));
            return user;
        });
    }

    @Transactional(readOnly = true)
    public Optional<User> findExisting(VerifiedSocialIdentity identity) {
        return find(identity);
    }

    private Optional<User> find(VerifiedSocialIdentity identity) {
        return socialAccountRepository.findByProviderAndProviderSubject(identity.provider(), identity.subject())
                .map(account -> userRepository.findById(account.getUserId())
                        .orElseThrow(() -> new IllegalStateException("social_accounts의 사용자가 없음")));
    }
}
