package com.baton.store.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.ColorUsage;
import com.baton.store.domain.InviteCode;
import com.baton.store.domain.InviteCodeGenerator;
import com.baton.store.domain.InviteCodeRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.PersonalColorPalette;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreName;
import com.baton.store.domain.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ST-01 매장 생성, ST-03 코드 참여. 둘 다 사용자 행을 잠가 가입 단계 전이를 직렬화한다.
 * 잠금 순서: (idempotency_records) → user → membership.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StoreCommandService {

    public static final String CODE_FIELD = "code";
    static final int MAX_CODE_ATTEMPTS = 5;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9]{8}$");

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final MembershipRepository membershipRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;

    /**
     * 매장 + 생성자 MANAGER 소속 + 초대 코드를 한 트랜잭션으로 만든다.
     * 활성 소속이 이미 있으면 ALREADY_IN_STORE(연속 클릭 두 번째 요청 포함), 매니저 매장 연결 단계가 아니면 FORBIDDEN.
     */
    public CreatedStore create(long userId, String rawName) {
        User user = lockUser(userId);
        if (membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE).isPresent()) {
            throw new BusinessException(ErrorCode.ALREADY_IN_STORE);
        }
        requireStoreStage(user, Role.MANAGER);
        String name = StoreName.normalize(rawName);
        Instant now = clock.instant();

        Store store = storeRepository.save(Store.open(name, now));
        Membership membership = saveMembership(
                Membership.join(store.getId(), userId, Role.MANAGER, PersonalColorPalette.pick(Map.of()), now));
        String code = issueInviteCode(store.getId(), now);
        user.joinStore();
        return new CreatedStore(store.getId(), store.getName(), membership.getId(), code);
    }

    /**
     * 유효 코드면 승인 단계 없이 ACTIVE CREW 소속을 만든다. 같은 매장에 이미 활성 소속이면 기존 소속을 돌려준다.
     */
    public JoinedStore join(long userId, String rawCode) {
        User user = lockUser(userId);
        if (user.getSelectedRole() != Role.CREW) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String code = normalizeCode(rawCode);
        InviteCode inviteCode = inviteCodeRepository.findByCodeAndInvalidatedAtIsNull(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITE_CODE));
        Store store = storeRepository.findById(inviteCode.getStoreId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INVITE_CODE));

        Optional<Membership> active = membershipRepository.findByUserIdAndState(userId, MembershipState.ACTIVE);
        if (active.isPresent()) {
            Membership existing = active.get();
            if (!existing.getStoreId().equals(store.getId())) {
                throw new BusinessException(ErrorCode.ALREADY_IN_STORE);
            }
            return new JoinedStore(store.getId(), store.getName(), existing.getId(), existing.getRole());
        }
        requireStoreStage(user, Role.CREW);

        Instant now = clock.instant();
        Membership membership = saveMembership(
                Membership.join(store.getId(), userId, Role.CREW, pickColor(store.getId()), now));
        user.joinStore();
        return new JoinedStore(store.getId(), store.getName(), membership.getId(), membership.getRole());
    }

    private User lockUser(long userId) {
        return userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private static void requireStoreStage(User user, Role role) {
        if (user.getOnboardingStage() != OnboardingStage.STORE_REQUIRED || user.getSelectedRole() != role) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /** 계정당 활성 소속 1개(memberships_user_active_uk)는 DB가 최종 보장한다. */
    private Membership saveMembership(Membership membership) {
        try {
            return membershipRepository.saveAndFlush(membership);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_IN_STORE);
        }
    }

    private String pickColor(long storeId) {
        Map<String, Long> usage = membershipRepository.countActiveColorUsage(storeId).stream()
                .collect(Collectors.toMap(ColorUsage::color, ColorUsage::count));
        return PersonalColorPalette.pick(usage);
    }

    /** 코드 충돌(invite_codes_code_uk) 시 새 코드로 최대 5회. */
    private String issueInviteCode(long storeId, Instant now) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (inviteCodeRepository.insertIfAbsent(storeId, code, now) == 1) {
                return code;
            }
        }
        throw new IllegalStateException("초대 코드 발급 실패: " + MAX_CODE_ATTEMPTS + "회 연속 충돌");
    }

    private static String normalizeCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip();
        if (!CODE_PATTERN.matcher(code).matches()) {
            String reason = code.isEmpty() ? "매장 코드를 입력해 주세요." : "영문 대문자·숫자 8자리로 입력해 주세요.";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(CODE_FIELD, reason)));
        }
        return code;
    }

    public record CreatedStore(long storeId, String name, long membershipId, String inviteCode) {
    }

    public record JoinedStore(long storeId, String storeName, long membershipId, Role role) {
    }
}
