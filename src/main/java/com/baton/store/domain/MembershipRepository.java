package com.baton.store.domain;

import com.baton.member.domain.Role;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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

    List<Membership> findByStoreIdAndStateAndRoleOrderByIdAsc(Long storeId, MembershipState state, Role role);

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

    /** 근무 일괄 등록 미리보기: 지정한 소속 중 그 매장 ACTIVE인 것(이름 포함). */
    @Query("""
            select new com.baton.store.domain.MemberRow(m.id, m.userId, u.displayName, m.role, m.personalColor)
            from Membership m join User u on u.id = m.userId
            where m.storeId = :storeId and m.id in :ids
              and m.state = com.baton.store.domain.MembershipState.ACTIVE
            """)
    List<MemberRow> findActiveMemberRowsByIds(@Param("storeId") Long storeId, @Param("ids") Collection<Long> ids);

    /**
     * SELECT ... FOR SHARE, id 오름차순. 근무 일괄 등록 중 담당자가 나가기·탈퇴(FOR UPDATE)로 비활성화되지 않게 한다.
     * 일괄 등록끼리는 서로 막지 않는다. 잠금 순서: membership → shift → substitute_request.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select m from Membership m
            where m.storeId = :storeId and m.id in :ids
              and m.state = com.baton.store.domain.MembershipState.ACTIVE
            order by m.id
            """)
    List<Membership> findActiveByIdsForShare(@Param("storeId") Long storeId, @Param("ids") Collection<Long> ids);

    /** SELECT ... FOR UPDATE. 잠금 순서: membership → shift → substitute_request. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id and m.state = com.baton.store.domain.MembershipState.ACTIVE")
    Optional<Membership> findActiveByIdForUpdate(@Param("id") Long id);

    /** 나가기: 이 매장의 본인 활성 소속을 FOR UPDATE. B의 수락·승인(lockActiveById)과 같은 행을 잠가 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from Membership m
            where m.storeId = :storeId and m.userId = :userId
              and m.state = com.baton.store.domain.MembershipState.ACTIVE
            """)
    Optional<Membership> findActiveByStoreAndUserForUpdate(@Param("storeId") Long storeId,
                                                           @Param("userId") Long userId);

    /** 탈퇴: 본인 소속 전부(ACTIVE·LEFT)를 id 오름차순 FOR UPDATE. 잠금 순서의 첫 단계(membership). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.userId = :userId order by m.id")
    List<Membership> findAllByUserIdForUpdate(@Param("userId") Long userId);
}
