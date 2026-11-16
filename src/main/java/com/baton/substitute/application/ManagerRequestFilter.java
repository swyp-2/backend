package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.substitute.domain.SubstituteStatus;
import java.util.List;

/**
 * 매니저 요청 탭: 요청(승인 대기) / 승인 / 거절(매니저 최종 거절). 쿼리 파라미터 값은 상태 이름 그대로다.
 */
public enum ManagerRequestFilter {
    PENDING_MANAGER(SubstituteStatus.PENDING_MANAGER),
    APPROVED(SubstituteStatus.APPROVED),
    REJECTED(SubstituteStatus.REJECTED);

    private final SubstituteStatus status;

    ManagerRequestFilter(SubstituteStatus status) {
        this.status = status;
    }

    public SubstituteStatus status() {
        return status;
    }

    /** status=PENDING_MANAGER|APPROVED|REJECTED. 없거나 다른 값이면 VALIDATION_FAILED. */
    public static ManagerRequestFilter parse(String value) {
        for (ManagerRequestFilter filter : values()) {
            if (filter.name().equals(value)) {
                return filter;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                List.of(new FieldError("status", "PENDING_MANAGER, APPROVED, REJECTED 중 하나로 보내 주세요.")));
    }
}
