package com.baton.store.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.member.domain.Role;
import com.baton.store.domain.InviteCodeRepository;
import com.baton.store.domain.MemberRow;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreQueryService {

    private final MembershipGuard membershipGuard;
    private final StoreRepository storeRepository;
    private final MembershipRepository membershipRepository;
    private final InviteCodeRepository inviteCodeRepository;

    /**
     * PR-02: 소속 매장 기본 정보. 활성 소속 검사가 먼저라 소속이 없거나 없는 매장이면 모두 403(존재 여부 비노출).
     * member_count는 실제 ACTIVE 소속 수다(시안의 15명을 쓰지 않는다, P13).
     */
    public StoreView getStore(long userId, long storeId) {
        ActiveMembership me = membershipGuard.requireActive(userId, storeId);
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        long memberCount = membershipRepository.countByStoreIdAndState(storeId, MembershipState.ACTIVE);
        return new StoreView(store.getId(), store.getName(), memberCount, Role.valueOf(me.role()));
    }

    /** ST-02/PR-02: 매장 공통 초대 코드. 해당 매장 매니저만. */
    public String getInviteCode(long userId, long storeId) {
        membershipGuard.requireManager(userId, storeId);
        return inviteCodeRepository.findByStoreIdAndInvalidatedAtIsNull(storeId)
                .map(code -> code.getCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * PRO-01/ED-01/RQ-01: 매장 구성원. 매니저 먼저, 한국어 이름순, 같으면 membership_id 순.
     * SUBSTITUTE_CANDIDATES는 본인·매니저를 뺀 ACTIVE CREW.
     */
    public List<MemberView> getMembers(long userId, long storeId, MemberPurpose purpose) {
        ActiveMembership me = membershipGuard.requireActive(userId, storeId);
        Collator collator = Collator.getInstance(Locale.KOREAN);
        return membershipRepository.findActiveMemberRows(storeId).stream()
                .filter(row -> purpose == MemberPurpose.ALL
                        || (row.role() == Role.CREW && row.membershipId() != me.membershipId()))
                .sorted(Comparator.comparing((MemberRow row) -> row.role() != Role.MANAGER)
                        .thenComparing(MemberRow::displayName, collator)
                        .thenComparing(MemberRow::membershipId))
                .map(row -> new MemberView(row.membershipId(), row.displayName(), row.role(), row.personalColor(),
                        row.membershipId() == me.membershipId()))
                .toList();
    }

    public enum MemberPurpose {
        ALL,
        SUBSTITUTE_CANDIDATES
    }

    public record StoreView(long storeId, String name, long memberCount, Role myRole) {
    }

    public record MemberView(long membershipId, String displayName, Role role, String personalColor, boolean isMe) {
    }
}
