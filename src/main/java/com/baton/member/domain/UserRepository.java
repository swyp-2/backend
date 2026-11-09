package com.baton.member.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByIdAndAccountState(Long id, AccountState accountState);

    /** SELECT ... FOR UPDATE. 가입 단계 전이를 직렬화한다. 잠금 순서 제안: user → membership → shift → substitute_request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.accountState = com.baton.member.domain.AccountState.ACTIVE")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);
}
