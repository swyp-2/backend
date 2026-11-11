package com.baton.notification.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 읽음·확인 처리는 "그 문장이 실행될 때 저장이 끝나 보이는 알림"만 바꾼다. 그 뒤에 저장되는 알림은 미읽음으로 남는다.
 * 시퀀스 id·생성 시각은 저장(커밋) 순서와 일치하지 않으므로 워터마크로 쓰지 않는다.
 *
 * <p>여러 행을 바꾸는 UPDATE는 id 오름차순으로 행을 잠근 뒤 바꾼다. 같은 수신자의 open·check가 동시에 와도
 * 서로 다른 순서로 잠가 교착되지 않게 하기 위해서다. 알림 생성은 INSERT뿐이라 이 잠금과 겹치지 않는다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 하단 바 점: 보관 중(만료 전) 미읽음 알림이 있는지. */
    boolean existsByRecipientMembershipIdAndReadAtIsNullAndExpiresAtAfter(Long recipientMembershipId, Instant now);

    /** 요청 탭 진입: 보관 중 미읽음 알림 전부를 읽음 처리. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE notifications SET read_at = :now
            WHERE id IN (
                SELECT id FROM notifications
                WHERE recipient_membership_id = :recipient AND read_at IS NULL AND expires_at > :now
                ORDER BY id
                FOR UPDATE)
            """, nativeQuery = true)
    int markAllRead(@Param("recipient") long recipientMembershipId, @Param("now") Instant now);

    /** 요청 상세 진입: 그 요청의 보관 중 미확인 알림을 확인 처리(미읽음이면 읽음도). 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE notifications SET checked_at = :now, read_at = COALESCE(read_at, :now)
            WHERE id IN (
                SELECT id FROM notifications
                WHERE recipient_membership_id = :recipient AND request_id = :requestId
                  AND checked_at IS NULL AND expires_at > :now
                ORDER BY id
                FOR UPDATE)
            """, nativeQuery = true)
    int markRequestChecked(@Param("recipient") long recipientMembershipId, @Param("requestId") long requestId,
                           @Param("now") Instant now);

    /** 카드 점: requestIds 중 보관 중 미확인 알림이 있는 요청 id. */
    @Query("""
            select distinct n.requestId from Notification n
            where n.recipientMembershipId = :recipient and n.requestId in :requestIds
              and n.checkedAt is null and n.expiresAt > :now
            """)
    List<Long> findUncheckedRequestIds(@Param("recipient") Long recipientMembershipId,
                                       @Param("requestIds") Collection<Long> requestIds, @Param("now") Instant now);

    /**
     * 보관 기간(생성 후 168시간)이 끝난 알림 삭제. 조회·점 계산은 이미 expires_at > now로 제외하므로
     * 삭제가 늦어도 노출되지 않는다. 삭제한 행 수를 돌려준다.
     * 읽음·확인 UPDATE와 같은 규칙으로 id 오름차순으로 잠근 뒤 지운다(만료 경계의 행이 겹쳐도 교착 방지).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM notifications
            WHERE id IN (
                SELECT id FROM notifications
                WHERE expires_at <= :now
                ORDER BY id
                FOR UPDATE)
            """, nativeQuery = true)
    int deleteExpired(@Param("now") Instant now);
}
