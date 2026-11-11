package com.baton.notification.application;

import com.baton.notification.domain.NotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 생성 후 7일(168시간)이 지난 알림을 매시 정각에 지운다(정책 D16·N08, ERD 보관 기준).
 *
 * <p>조회·점 계산은 만료 시각부터 이미 제외하므로, 정리가 최대 1시간 늦어도 만료 알림이 노출되지는 않는다.
 * 알림만 지우고 대타 요청·진행 기록은 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class NotificationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupJob.class);

    private final NotificationRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("보관 기간이 지난 알림 {}건 삭제", deleted);
        }
    }
}
