package com.baton.substitute.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Long> {

    /** 근무의 진행 중 요청(부분 유니크로 최대 1건). statuses에는 진행 중 상태만 넘긴다. */
    Optional<SubstituteRequest> findFirstByShiftIdAndStatusIn(Long shiftId, Collection<SubstituteStatus> statuses);

    /**
     * 잠그기 전 관계 확인용 스냅샷. 엔티티를 영속성 컨텍스트에 올리지 않아 뒤의 잠금 조회가 최신 값을 읽는다.
     */
    @Query("""
            select new com.baton.substitute.domain.RequestParties(
                r.id, r.storeId, r.shiftId, r.requesterMembershipId, r.candidateMembershipId)
            from SubstituteRequest r where r.id = :id
            """)
    Optional<RequestParties> findPartiesById(@Param("id") Long id);

    /** SELECT ... FOR UPDATE. 잠금 순서의 마지막(membership → shift → substitute_request). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstituteRequest r where r.id = :id")
    Optional<SubstituteRequest> findByIdForUpdate(@Param("id") Long id);

    // 보낸 요청: 생성 시각·ID 내림차순 커서 ---------------------------------

    @Query("""
            select r from SubstituteRequest r
            where r.requesterMembershipId = :membershipId
            order by r.createdAt desc, r.id desc
            """)
    List<SubstituteRequest> findSentFirstPage(@Param("membershipId") Long membershipId, Limit limit);

    @Query("""
            select r from SubstituteRequest r
            where r.requesterMembershipId = :membershipId
              and (r.createdAt < :createdAt or (r.createdAt = :createdAt and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<SubstituteRequest> findSentAfter(@Param("membershipId") Long membershipId,
                                          @Param("createdAt") Instant createdAt, @Param("id") Long id, Limit limit);

    // 받은 요청: 본인이 지정 대상인 요청 ------------------------------------

    @Query("""
            select r from SubstituteRequest r, RequestRecipient x
            where x.requestId = r.id and x.targetMembershipId = :membershipId
            order by r.createdAt desc, r.id desc
            """)
    List<SubstituteRequest> findReceivedFirstPage(@Param("membershipId") Long membershipId, Limit limit);

    @Query("""
            select r from SubstituteRequest r, RequestRecipient x
            where x.requestId = r.id and x.targetMembershipId = :membershipId
              and (r.createdAt < :createdAt or (r.createdAt = :createdAt and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<SubstituteRequest> findReceivedAfter(@Param("membershipId") Long membershipId,
                                              @Param("createdAt") Instant createdAt, @Param("id") Long id,
                                              Limit limit);
}
