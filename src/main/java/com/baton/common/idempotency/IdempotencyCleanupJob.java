package com.baton.common.idempotency;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보존 기간(24시간)이 지난 멱등성 기록을 매시 정각에 지운다.
 * 정리 전의 만료 기록은 선점 시 새 요청으로 덮어쓰므로, 정리가 늦어도 동작에는 영향이 없다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRecordRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpired(clock.instant());
        if (deleted > 0) {
            log.info("만료된 멱등성 기록 {}건 삭제", deleted);
        }
    }
}
