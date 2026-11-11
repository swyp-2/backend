package com.baton.substitute.application;

import com.baton.substitute.domain.RequestEvent;
import com.baton.substitute.domain.RequestEventKind;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import com.baton.substitute.domain.SubstituteStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근무 시작 시각에 도달한 진행 중 요청을 EXPIRED로 바꾼다(기능명세서 SUB-10, 정책 B15).
 *
 * <p>만료는 알림을 만들지 않는다(정책 이벤트 표 "근무 시작에 따른 만료 | 없음"). 원래 담당자는 그대로다.
 * 진행 기록은 REQUEST_EXPIRED, 행위자 없음(시스템 처리)으로 남긴다.
 *
 * <p>잠금: substitute_request 행 하나만 잠근다. 소속·근무를 바꾸지 않으므로 잠금 순서
 * (idempotency → membership → shift → substitute_request)의 마지막 단계만 쓰고 교착이 생기지 않는다.
 * 같은 요청의 수락·승인 등과는 이 행 잠금으로 직렬화되고, 잠근 뒤 상태와 시각을 다시 확인한다.
 */
@Service
@RequiredArgsConstructor
public class SubstituteRequestExpiryService {

    private final SubstituteRequestRepository requestRepository;
    private final RequestEventRepository eventRepository;
    private final Clock clock;

    /** now 기준 만료 대상 요청 id. afterId 다음부터 id 오름차순으로 최대 size건. */
    @Transactional(readOnly = true)
    public List<Long> findExpirableIds(Instant now, long afterId, int size) {
        return requestRepository.findExpirableIds(now, afterId, Limit.of(size));
    }

    /**
     * 요청 1건을 만료한다. 이미 종료됐거나(다른 처리가 먼저 커밋됨) 아직 시작 전이면 바꾸지 않는다.
     *
     * @return 이번 호출로 EXPIRED가 됐으면 true
     */
    @Transactional
    public boolean expire(long requestId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SubstituteRequest request = requestRepository.findByIdForUpdate(requestId).orElse(null);
        if (request == null || !request.isActive() || !request.hasStarted(now)) {
            return false;
        }
        SubstituteStatus before = request.getStatus();
        request.expire(now);
        eventRepository.save(RequestEvent.record(request.getId(), null, RequestEventKind.REQUEST_EXPIRED,
                before, request.getStatus(), now));
        return true;
    }
}
