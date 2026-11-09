package com.baton.substitute.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Long> {

    /** 근무의 진행 중 요청(부분 유니크로 최대 1건). statuses에는 진행 중 상태만 넘긴다. */
    Optional<SubstituteRequest> findFirstByShiftIdAndStatusIn(Long shiftId, Collection<SubstituteStatus> statuses);

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
