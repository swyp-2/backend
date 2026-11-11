package com.baton.member.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    List<UserConsent> findByUserIdOrderByDocumentIdAsc(Long userId);

    /** 탈퇴: 동의 기록 삭제(별도 법적 보존 근거 없음, ERD 6절). */
    @Modifying
    @Query("delete from UserConsent c where c.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
