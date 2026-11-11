package com.baton.substitute.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매분 0초에 시작 시각이 지난 진행 중 요청을 EXPIRED로 바꾼다.
 *
 * <p>시작 시각 이후의 행동은 API가 이미 REQUEST_EXPIRED로 막으므로, 주기는 화면 상태가 EXPIRED로 보이기까지의
 * 지연(최대 약 1분)에만 영향을 준다.
 *
 * <p>요청 1건마다 트랜잭션을 따로 쓴다. 한 건이 실패해도 로그만 남기고 다음 건을 처리하며, 실패한 건은 다음 실행에서
 * 다시 시도한다. id 키셋으로 넘기므로 실패 건이 있어도 같은 실행 안에서 반복하지 않는다.
 * 서버가 여러 대여도 잠근 뒤 다시 확인하므로 같은 요청을 두 번 만료하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SubstituteRequestExpiryJob {

    static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(SubstituteRequestExpiryJob.class);

    private final SubstituteRequestExpiryService expiryService;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void expireStarted() {
        Instant now = clock.instant();
        long afterId = 0;
        int expired = 0;
        while (true) {
            List<Long> ids = expiryService.findExpirableIds(now, afterId, BATCH_SIZE);
            for (Long id : ids) {
                try {
                    if (expiryService.expire(id)) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    log.warn("대타 요청 {} 만료 처리 실패. 다음 실행에서 다시 시도한다", id, e);
                }
            }
            if (ids.size() < BATCH_SIZE) {
                break;
            }
            afterId = ids.getLast();
        }
        if (expired > 0) {
            log.info("시작 시각이 지난 대타 요청 {}건 만료", expired);
        }
    }
}
