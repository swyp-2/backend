package com.baton.notification.application;

import com.baton.notification.domain.Notification;
import com.baton.notification.domain.NotificationRepository;
import com.baton.substitute.domain.RequestEvent;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청 이벤트의 앱 내부 알림 생성. 업무 상태 변경과 같은 트랜잭션에서 저장해 실패한 처리의 알림이 남지 않게 한다.
 *
 * <p>수신자 계산(요청자·후보·매니저 합집합, 현재 활성 소속만)은 호출자가 한다. 여기서는 같은 사람 중복만 제거한다.
 */
@Service
@RequiredArgsConstructor
public class NotificationWriter {

    private final NotificationRepository notificationRepository;

    /** 이벤트 1건 × 수신자마다 알림 1건. 만료·근무 삭제처럼 알림을 만들지 않는 이벤트면 아무것도 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notify(RequestEvent event, Collection<Long> recipientMembershipIds) {
        if (!event.getKind().createsNotification()) {
            return;
        }
        List<Notification> notifications = new LinkedHashSet<>(recipientMembershipIds).stream()
                .map(recipient -> Notification.of(recipient, event.getRequestId(), event.getId(), event.getKind(),
                        event.getOccurredAt()))
                .toList();
        notificationRepository.saveAll(notifications);
    }
}
