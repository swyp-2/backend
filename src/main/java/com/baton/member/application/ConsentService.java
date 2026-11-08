package com.baton.member.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.member.domain.ConsentDocument;
import com.baton.member.domain.ConsentDocumentRepository;
import com.baton.member.domain.User;
import com.baton.member.domain.UserConsent;
import com.baton.member.domain.UserConsentRepository;
import com.baton.member.domain.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CO-02/CO-03 약관. 조회(보기·닫기)는 동의를 저장하지 않는다. 필수 여부는 kind가 아니라 required 컬럼으로 판단한다.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentDocumentRepository consentDocumentRepository;
    private final UserConsentRepository userConsentRepository;
    private final UserRepository userRepository;
    private final MeService meService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ConsentDocumentView> getCurrentDocuments() {
        return consentDocumentRepository.findCurrent(clock.instant()).stream()
                .map(ConsentDocumentView::from)
                .toList();
    }

    /**
     * 현재 유효한 문서 전부에 대해 동의 여부를 기록한다. 요청에 없는 선택 문서는 agreed=false로 기록한다.
     * agreed_at은 서버 Clock 시각(동의 여부를 결정한 시각). 클라이언트 시각은 받지 않는다.
     */
    @Transactional
    public MeView agree(long userId, List<AgreementCommand> agreements) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        // 단계 검사가 먼저: 이미 지난 사용자는 입력과 무관하게 409이고 기존 기록을 덮어쓰지 않는다.
        // 아래 검증이 실패하면 트랜잭션 롤백으로 단계 변경도 취소된다.
        user.agreeConsents();

        Instant now = clock.instant();
        List<ConsentDocument> current = consentDocumentRepository.findCurrent(now);
        Map<Long, Boolean> decisions = validate(agreements, current);

        List<UserConsent> records = current.stream()
                .map(doc -> UserConsent.decide(userId, doc.getId(), decisions.getOrDefault(doc.getId(), false), now))
                .toList();
        try {
            userConsentRepository.saveAllAndFlush(records);
        } catch (DataIntegrityViolationException e) {
            // user 잠금으로 직렬화되므로 정상 흐름에서는 발생하지 않는다. DB 유니크가 최종 방어선.
            throw BusinessException.withCurrentState(ErrorCode.ONBOARDING_STAGE_CONFLICT,
                    user.getOnboardingStage().name());
        }
        return meService.assemble(user);
    }

    private static Map<Long, Boolean> validate(List<AgreementCommand> agreements, List<ConsentDocument> current) {
        Set<Long> currentIds = new LinkedHashSet<>();
        current.forEach(doc -> currentIds.add(doc.getId()));

        List<FieldError> errors = new ArrayList<>();
        Map<Long, Boolean> decisions = new HashMap<>();
        Set<Long> duplicated = new LinkedHashSet<>();
        for (AgreementCommand agreement : agreements) {
            long documentId = agreement.documentId();
            if (decisions.containsKey(documentId)) {
                if (duplicated.add(documentId)) {
                    errors.add(fieldError(documentId, "같은 약관이 중복되었어요."));
                }
                continue;
            }
            decisions.put(documentId, agreement.agreed());
            if (!currentIds.contains(documentId)) {
                errors.add(fieldError(documentId, "현재 유효한 약관이 아니에요."));
            }
        }
        for (ConsentDocument doc : current) {
            if (doc.isRequired() && !Boolean.TRUE.equals(decisions.get(doc.getId()))) {
                errors.add(fieldError(doc.getId(), "필수 약관에 동의해 주세요."));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, errors);
        }
        return decisions;
    }

    /** field_errors의 field 형식: agreements[document_id=N] (프론트와 합의 필요). */
    public static String fieldOf(long documentId) {
        return "agreements[document_id=" + documentId + "]";
    }

    private static FieldError fieldError(long documentId, String reason) {
        return new FieldError(fieldOf(documentId), reason);
    }
}
