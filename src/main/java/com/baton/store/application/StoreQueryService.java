package com.baton.store.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreQueryService {

    private final MembershipGuard membershipGuard;
    private final StoreRepository storeRepository;

    /**
     * PR-02: 소속 매장 기본 정보. 활성 소속 검사가 먼저라 소속이 없거나 없는 매장이면 모두 403(존재 여부 비노출).
     */
    public StoreView getStore(long userId, long storeId) {
        membershipGuard.requireActive(userId, storeId);
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        return new StoreView(store.getId(), store.getName());
    }

    public record StoreView(long storeId, String name) {
    }
}
