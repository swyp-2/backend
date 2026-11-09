package com.baton.shift.domain;

import java.time.Instant;
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

    @Query(ROW_SELECT + "where s.id = :shiftId and s.deletedAt is null")
    Optional<ShiftRow> findLiveRowById(@Param("shiftId") Long shiftId);
}
