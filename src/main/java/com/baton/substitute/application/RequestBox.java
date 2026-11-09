package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.util.List;

/** RQ-02 탭: 보낸 요청(본인이 요청자) / 받은 요청(본인이 지정 대상). */
public enum RequestBox {
    SENT,
    RECEIVED;

    /** 쿼리 파라미터 box=sent|received. 없거나 다른 값이면 VALIDATION_FAILED. */
    public static RequestBox parse(String value) {
        if ("sent".equals(value)) {
            return SENT;
        }
        if ("received".equals(value)) {
            return RECEIVED;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                List.of(new FieldError("box", "sent 또는 received 중 하나로 보내 주세요.")));
    }
}
