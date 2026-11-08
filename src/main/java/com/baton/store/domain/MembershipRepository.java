package com.baton.store.domain;

import com.baton.member.domain.Role;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByUserIdAndState(Long userId, MembershipState state);

    Optional<Membership> findByStoreIdAndUserIdAndState(Long storeId, Long userId, MembershipState state);

    List<Membership> findByStoreIdAndStateAndRoleAndIdNotOrderByIdAsc(Long storeId, MembershipState state, Role role,
                                                                       Long excludeId);

    /** SELECT ... FOR UPDATE. 잠금 순서: membership → shift → substitute_request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id and m.state = com.baton.store.domain.MembershipState.ACTIVE")
    Optional<Membership> findActiveByIdForUpdate(@Param("id") Long id);
}
