package com.baton.auth.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderSubject(SocialProvider provider, String providerSubject);

    /** 탈퇴: 소셜 식별자 파기. 같은 소셜 계정으로 다시 로그인하면 새 회원이 된다. */
    @Modifying
    @Query("delete from SocialAccount a where a.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
