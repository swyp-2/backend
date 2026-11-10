package com.baton.shift.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.integration.ActiveMembership;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftEvent;
import com.baton.shift.domain.ShiftEventKind;
import com.baton.shift.domain.ShiftEventRepository;
import com.baton.shift.domain.ShiftKey;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.application.MembershipGuard;
import com.baton.store.domain.MemberRow;
import com.baton.store.domain.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-05 근무 일괄 등록(정책 B03·B05·B06·B20).
 * <ul>
 *   <li>미리보기: 쓰기 없음. 계획 + 완전 동일 중복 표시 + 서명 토큰</li>
 *   <li>최종: 권한·계획·구성원·중복을 다시 계산하고, 미리보기 이후 달라졌으면 PREVIEW_STALE.
 *       새 근무와 CREATED 기록을 한 트랜잭션에서 전부 저장하거나 전부 롤백한다</li>
 * </ul>
 * 권한 근거는 인증 사용자와 DB 활성 소속이다. 본문 store_id는 대상 매장 지정일 뿐이다.
 * 잠금 순서: (idempotency_records) → membership(FOR SHARE) → shift(유니크 인덱스).
 */
@Service
@RequiredArgsConstructor
public class ShiftBatchService {

    public static final String F_STORE_ID = "store_id";
    public static final String F_PREVIEW_TOKEN = "preview_token";
    static final String IDENTICAL_UNIQUE = "shifts_identical_live_uk";

    private final MembershipGuard membershipGuard;
    private final MembershipRepository membershipRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftEventRepository shiftEventRepository;
    private final ShiftPlanCalculator calculator;
    private final PreviewTokenCodec tokenCodec;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Preview preview(long userId, Long storeId, ShiftBatchInput input) {
        ActiveMembership manager = requireManager(userId, storeId);
        ShiftPlan plan = calculator.plan(input);

        Map<Long, MemberRow> members = membershipRepository
                .findActiveMemberRowsByIds(storeId, input.assigneeMembershipIds()).stream()
                .collect(Collectors.toMap(MemberRow::membershipId, Function.identity()));
        requireAllActive(members.size(), input);

        Set<ShiftKey> existing = existingKeys(storeId, plan);
        List<PreviewItem> items = new ArrayList<>(plan.size());
        List<PlannedShift> toCreate = new ArrayList<>();
        for (PlannedShift planned : plan.shifts()) {
            boolean duplicate = existing.contains(keyOf(planned));
            if (!duplicate) {
                toCreate.add(planned);
            }
            items.add(new PreviewItem(planned.membershipId(), members.get(planned.membershipId()).displayName(),
                    planned.localDate(), planned.startsAt(), planned.endsAt(), duplicate));
        }

        String token = tokenCodec.issue(new PreviewTokenCodec.Claims(storeId, manager.membershipId(),
                ShiftBatchHashes.inputHash(storeId, input), ShiftBatchHashes.planHash(toCreate)));
        return new Preview(token, toCreate.size(), plan.size() - toCreate.size(), plan.size(), items);
    }

    /** IdempotencyService 트랜잭션 안에서 호출된다. 예외면 선점 기록까지 함께 롤백된다. */
    @Transactional
    public Created create(long userId, Long storeId, ShiftBatchInput input, String previewToken) {
        ActiveMembership manager = requireManager(userId, storeId);
        if (previewToken == null || previewToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    List.of(new FieldError(F_PREVIEW_TOKEN, "미리보기를 먼저 확인해 주세요.")));
        }
        PreviewTokenCodec.Claims claims = tokenCodec.verify(previewToken);
        if (claims.storeId() != storeId || claims.managerMembershipId() != manager.membershipId()
                || !claims.inputHash().equals(ShiftBatchHashes.inputHash(storeId, input))) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }

        ShiftPlan plan = calculator.plan(input);
        int lockedActive = membershipRepository
                .findActiveByIdsForShare(storeId, input.assigneeMembershipIds()).size();
        requireAllActive(lockedActive, input);

        Set<ShiftKey> existing = existingKeys(storeId, plan);
        List<PlannedShift> toCreate = plan.shifts().stream()
                .filter(planned -> !existing.contains(keyOf(planned)))
                .toList();
        if (toCreate.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "모두 이미 등록된 근무예요.", List.of(), null);
        }
        if (!claims.planHash().equals(ShiftBatchHashes.planHash(toCreate))) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }

        Instant now = clock.instant();
        List<Shift> shifts = toCreate.stream()
                .map(p -> Shift.schedule(storeId, p.membershipId(), p.startsAt(), p.endsAt(), now))
                .toList();
        insertShifts(shifts);

        List<ShiftEvent> events = shifts.stream()
                .map(shift -> ShiftEvent.record(shift.getId(), ShiftEventKind.CREATED, null, afterValue(shift),
                        manager.membershipId(), null, now))
                .toList();
        shiftEventRepository.saveAll(events);
        shiftEventRepository.flush();

        List<Long> shiftIds = shifts.stream().map(Shift::getId).toList();
        return new Created(shiftIds.size(), plan.size() - toCreate.size(), shiftIds);
    }

    private ActiveMembership requireManager(long userId, Long storeId) {
        if (storeId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    List.of(new FieldError(F_STORE_ID, "매장을 지정해 주세요.")));
        }
        return membershipGuard.requireManager(userId, storeId);
    }

    /** 계산기가 담당자 중복·null을 이미 걸렀으므로 개수 비교로 충분하다. */
    private static void requireAllActive(int activeCount, ShiftBatchInput input) {
        if (activeCount != input.assigneeMembershipIds().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(
                    ShiftPlanCalculator.F_ASSIGNEES, "이 매장의 현재 구성원만 근무자로 선택할 수 있어요.")));
        }
    }

    /** 한 번의 쿼리로 계획 범위의 기존 유효 근무 키를 가져온다. 일부만 겹치는 근무는 중복이 아니다(B05). */
    private Set<ShiftKey> existingKeys(long storeId, ShiftPlan plan) {
        List<PlannedShift> shifts = plan.shifts();
        Set<Long> assignees = shifts.stream().map(PlannedShift::membershipId).collect(Collectors.toSet());
        return new HashSet<>(shiftRepository.findLiveKeys(storeId, assignees,
                shifts.getFirst().startsAt(), shifts.getLast().startsAt()));
    }

    private static ShiftKey keyOf(PlannedShift planned) {
        return new ShiftKey(planned.membershipId(), planned.startsAt(), planned.endsAt());
    }

    /** 그 사이 누가 같은 근무를 등록해 부분 유니크에 걸리면 전체 롤백 후 PREVIEW_STALE. */
    private void insertShifts(List<Shift> shifts) {
        try {
            shiftRepository.saveAll(shifts);
            shiftRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (isIdenticalShiftViolation(e)) {
                throw new BusinessException(ErrorCode.PREVIEW_STALE);
            }
            throw e;
        }
    }

    private static boolean isIdenticalShiftViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName().contains(IDENTICAL_UNIQUE);
            }
            if (t.getMessage() != null && t.getMessage().contains(IDENTICAL_UNIQUE)) {
                return true;
            }
        }
        return false;
    }

    private String afterValue(Shift shift) {
        return jsonMapper.writeValueAsString(new ShiftValue(shift.getAssigneeMembershipId(), shift.getStartsAt(),
                shift.getEndsAt()));
    }

    record ShiftValue(long assigneeMembershipId, Instant startsAt, Instant endsAt) {
    }

    public record Preview(String previewToken, int createCount, int duplicateCount, int totalCount,
                          List<PreviewItem> items) {
    }

    public record PreviewItem(long membershipId, String displayName, LocalDate localDate, Instant startsAt,
                              Instant endsAt, boolean duplicate) {
    }

    public record Created(int createdCount, int duplicateCount, List<Long> shiftIds) {
    }
}
