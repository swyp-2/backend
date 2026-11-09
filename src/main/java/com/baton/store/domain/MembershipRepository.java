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

    long countByStoreIdAndState(Long storeId, MembershipState state);

    @Query("""
            select new com.baton.store.domain.ColorUsage(m.personalColor, count(m))
            from Membership m
            where m.storeId = :storeId and m.state = com.baton.store.domain.MembershipState.ACTIVE
            group by m.personalColor
            """)
    List<ColorUsage> countActiveColorUsage(@Param("storeId") Long storeId);

    @Query("""
            select new com.baton.store.domain.MemberRow(m.id, m.userId, u.displayName, m.role, m.personalColor)
            from Membership m join User u on u.id = m.userId
            where m.storeId = :storeId and m.state = com.baton.store.domain.MembershipState.ACTIVE
            """)
    List<MemberRow> findActiveMemberRows(@Param("storeId") Long storeId);

    /** SELECT ... FOR UPDATE. 잠금 순서: membership → shift → substitute_request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id and m.state = com.baton.store.domain.MembershipState.ACTIVE")
    Optional<Membership> findActiveByIdForUpdate(@Param("id") Long id);
}
