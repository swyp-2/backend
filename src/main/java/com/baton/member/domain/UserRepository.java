package com.baton.member.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByIdAndAccountState(Long id, AccountState accountState);
}
