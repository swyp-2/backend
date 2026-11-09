package com.baton.member.domain;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import java.util.List;

/**
 * 가입 이름 검증(D14). 앞뒤 공백 제거 후 2~15자(코드 포인트 기준). 제어문자·줄바꿈은 위치와 관계없이 거부한다.
 */
public final class DisplayName {

    public static final String FIELD = "display_name";
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 15;

    private DisplayName() {
    }

    /** 검증을 통과하면 앞뒤 공백을 제거한 이름을, 아니면 VALIDATION_FAILED(field: display_name)를 던진다. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw invalid("이름을 입력해 주세요.");
        }
        if (raw.codePoints().anyMatch(DisplayName::isForbidden)) {
            throw invalid("이름에 줄바꿈이나 제어문자를 넣을 수 없어요.");
        }
        String stripped = raw.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw invalid("이름은 앞뒤 공백을 빼고 2~15자로 입력해 주세요.");
        }
        return stripped;
    }

    private static boolean isForbidden(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == 0x2028   // LINE SEPARATOR
                || codePoint == 0x2029;  // PARAGRAPH SEPARATOR
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(FIELD, reason)));
    }
}
