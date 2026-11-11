package com.baton.account.application;

import com.baton.auth.domain.AuthSessionRepository;
import com.baton.auth.domain.SocialAccountRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.idempotency.IdempotencyRecordRepository;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.User;
import com.baton.member.domain.UserConsentRepository;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRO-03: 매장 나가기·계정 탈퇴(정책 D05·D20·8절).
 *
 * <p>잠금 순서: user → membership → shift → substitute_request. 매장 생성·참여(user → membership)와 같고,
 * B의 수락·승인·생성(lockActiveById)과 일괄 등록(FOR SHARE)은 같은 membership 행에서 직렬화된다.
 * 확정 직전에 미종료 본인 배정 근무를 다시 확인하고, 있으면 아무것도 바꾸지 않는다.
 * 소속 해제·요청 정리(B)·개인정보 파기는 한 트랜잭션에서 함께 확정하므로 어느 하나가 실패하면 전부 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final ShiftRepository shiftRepository;
    private final AuthSessionRepository authSessionRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final UserConsentRepository userConsentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final SubstituteRequestLifecycle substituteRequestLifecycle;
    private final Clock clock;

    /**
     * 매장 나가기. 계정·세션은 유지하고 소속만 해제한다(state=LEFT, 가입 단계 STORE_REQUIRED).
     * 이 매장의 활성 소속이 없으면(다른 매장·이미 나감) 403.
     */
    @Transactional
    public void leave(long userId, long storeId) {
        User user = lockUser(userId);
        Membership membership = membershipRepository.findActiveByStoreAndUserForUpdate(storeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        Instant now = clock.instant();
        releaseActive(membership, now);
        membership.leave(now);
        user.leaveStore();
        membershipRepository.flush();
    }

    /**
     * 계정 탈퇴. 활성 소속이 있으면 나가기와 같은 사전 검사·요청 정리를 먼저 하고,
     * 과거 소속을 포함한 모든 소속의 개인 연결과 인증·동의 기록을 파기한다. users 행은 개인 연결 없는 톰스톤으로 남긴다.
     */
    @Transactional
    public void withdraw(long userId) {
        User user = lockUser(userId);
        List<Membership> memberships = membershipRepository.findAllByUserIdForUpdate(userId);
        Instant now = clock.instant();
        for (Membership membership : memberships) {
            if (membership.isActive()) {
                releaseActive(membership, now);
            }
        }
        for (Membership membership : memberships) {
            substituteRequestLifecycle.anonymizeFor(membership.getId());
            membership.withdraw(now);
        }
        membershipRepository.flush();

        idempotencyRecordRepository.deleteByUserId(userId);
        authSessionRepository.deleteByUserId(userId);
        socialAccountRepository.deleteByUserId(userId);
        userConsentRepository.deleteByUserId(userId);
        user.withdraw(now);
        userRepository.flush();
    }

    /** 잠긴 활성 소속의 해제 전 공통 처리: 미종료 근무 검사 → (마지막 매니저 자리) → 요청 정리. */
    private void releaseActive(Membership membership, Instant now) {
        if (shiftRepository.existsUnfinishedByAssignee(membership.getId(), now)) {
            throw new BusinessException(ErrorCode.FUTURE_SHIFT_REMAINS);
        }
        // TODO(U-02): 마지막 매니저의 나가기·탈퇴 특수 처리는 후속 범위. 자동 위임·매장 폐쇄·관리자 승격을 여기서 하지 않는다.
        substituteRequestLifecycle.cleanupOnMembershipLeave(membership.getId());
    }

    private User lockUser(long userId) {
        return userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
