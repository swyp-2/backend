package com.baton.store.domain;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.util.List;

/**
 * 매장명 검증(D14). 앞뒤 공백 제거 후 1~15자(코드 포인트 기준). 제어문자·줄바꿈은 위치와 관계없이 거부한다.
 */
public final class StoreName {

    public static final String FIELD = "name";
    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 15;

    private StoreName() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw invalid("매장명을 입력해 주세요.");
        }
        if (raw.codePoints().anyMatch(StoreName::isForbidden)) {
            throw invalid("매장명에 줄바꿈이나 제어문자를 넣을 수 없어요.");
        }
        String stripped = raw.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw invalid("매장명은 앞뒤 공백을 빼고 1~15자로 입력해 주세요.");
        }
        return stripped;
    }

    private static boolean isForbidden(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(FIELD, reason)));
    }
}
