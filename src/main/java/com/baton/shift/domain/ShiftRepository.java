package com.baton.shift.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    String ROW_SELECT = """
            select new com.baton.shift.domain.ShiftRow(
                s.id, s.storeId, s.assigneeMembershipId, s.startsAt, s.endsAt, s.version,
                m.userId, u.displayName, u.accountState, m.personalColor)
            from Shift s
                join com.baton.store.domain.Membership m on m.id = s.assigneeMembershipId
                left join com.baton.member.domain.User u on u.id = m.userId
            """;

    /** 매장의 삭제되지 않은 근무 중 starts_at이 [from, to)인 것. shifts_store_starts_live_idx 사용. */
    @Query(ROW_SELECT + """
            where s.storeId = :storeId and s.deletedAt is null
              and s.startsAt >= :from and s.startsAt < :to
            """)
    List<ShiftRow> findLiveRowsInStore(@Param("storeId") Long storeId, @Param("from") Instant from,
                                       @Param("to") Instant to);

    /** 한 담당자의 삭제되지 않은 근무 중 starts_at이 [from, to)인 것. shifts_assignee_starts_idx 사용. */
    @Query(ROW_SELECT + """
            where s.storeId = :storeId and s.assigneeMembershipId = :assigneeMembershipId
              and s.deletedAt is null and s.startsAt >= :from and s.startsAt < :to
            """)
    List<ShiftRow> findLiveRowsOfAssignee(@Param("storeId") Long storeId,
                                          @Param("assigneeMembershipId") Long assigneeMembershipId,
                                          @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 일괄 등록 중복 확인: 담당자 목록의 삭제되지 않은 근무 중 starts_at이 [from, to](양 끝 포함)인 것의 키.
     * shifts_identical_live_uk(store_id, assignee_membership_id, starts_at, ends_at) 부분 인덱스를 탄다.
     */
    @Query("""
            select new com.baton.shift.domain.ShiftKey(s.assigneeMembershipId, s.startsAt, s.endsAt)
            from Shift s
            where s.storeId = :storeId and s.assigneeMembershipId in :assigneeIds and s.deletedAt is null
              and s.startsAt >= :from and s.startsAt <= :to
            """)
    List<ShiftKey> findLiveKeys(@Param("storeId") Long storeId, @Param("assigneeIds") Collection<Long> assigneeIds,
                                @Param("from") Instant from, @Param("to") Instant to);

    @Query(ROW_SELECT + "where s.id = :shiftId and s.deletedAt is null")
    Optional<ShiftRow> findLiveRowById(@Param("shiftId") Long shiftId);

    /**
     * 나가기·탈퇴 사전 검사: 삭제되지 않았고 아직 끝나지 않은(ends_at > now, 예정 + 진행 중) 본인 배정 근무가 있는지.
     * shifts_assignee_starts_idx로 담당자 범위를 좁힌다.
     */
    @Query("""
            select count(s) > 0 from Shift s
            where s.assigneeMembershipId = :membershipId and s.deletedAt is null and s.endsAt > :now
            """)
    boolean existsUnfinishedByAssignee(@Param("membershipId") Long membershipId, @Param("now") Instant now);
}
