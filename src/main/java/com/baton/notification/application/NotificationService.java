package com.baton.notification.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.notification.domain.NotificationRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 빨간 점 두 단계(2026-11-11 Figma 기준, 알림 목록 화면 없음).
 * <ul>
 *   <li>하단 바 "요청" 탭 점: 보관 중 미읽음 알림이 있으면 표시. 요청 탭 진입(open)으로 지운다.</li>
 *   <li>요청 카드 점: 그 요청에 보관 중 미확인 알림이 있으면 표시. 요청 상세 진입(check)으로 지운다.</li>
 * </ul>
 * 모두 현재 활성 소속의 알림만 다룬다(나간 매장의 알림은 보지 않는다). 활성 소속이 없으면 FORBIDDEN.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MembershipQuery membershipQuery;
    private final Clock clock;

    @Transactional(readOnly = true)
    public boolean hasUnread(long userId) {
        return notificationRepository.existsByRecipientMembershipIdAndReadAtIsNullAndExpiresAtAfter(
                activeMembership(userId).membershipId(), clock.instant());
    }

    /** 요청 탭 진입. 이 시점까지 저장된 알림만 읽음이 되고, 이후 도착한 알림은 미읽음으로 남는다. 재시도해도 안전하다. */
    @Transactional
    public void open(long userId) {
        notificationRepository.markAllRead(activeMembership(userId).membershipId(), clock.instant());
    }

    /**
     * 요청 상세 진입. 본인 알림 중 그 요청의 것만 확인 처리한다. 관계없는 요청·없는 요청이면 바뀌는 것이 없다
     * (요청 접근 권한은 상세 조회 API가 따로 검사한다).
     */
    @Transactional
    public void check(long userId, long requestId) {
        notificationRepository.markRequestChecked(activeMembership(userId).membershipId(), requestId,
                clock.instant());
    }

    /** 목록 카드 점 계산용. 호출자 트랜잭션(목록 조회)에서 쓴다. */
    @Transactional(readOnly = true)
    public Set<Long> uncheckedRequestIds(long membershipId, Collection<Long> requestIds) {
        if (requestIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = notificationRepository.findUncheckedRequestIds(membershipId, requestIds, clock.instant());
        return Set.copyOf(ids);
    }

    private ActiveMembership activeMembership(long userId) {
        return membershipQuery.findActiveByUser(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }
}
