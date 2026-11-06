package com.baton.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요. 다시 로그인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 할 권한이 없어요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없어요."),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "입력값을 다시 확인해 주세요."),
    INVALID_INVITE_CODE(HttpStatus.UNPROCESSABLE_CONTENT, "유효한 매장 코드를 다시 입력해 주세요."),
    ACTIVE_REQUEST_EXISTS(HttpStatus.CONFLICT, "이 근무에는 이미 진행 중인 대타 요청이 있어요."),
    REQUEST_STATE_CONFLICT(HttpStatus.CONFLICT, "요청 상태가 바뀌었어요. 최신 요청을 확인해 주세요."),
    REQUEST_EXPIRED(HttpStatus.CONFLICT, "근무 시작 시각이 지나 처리할 수 없어요."),
    PREVIEW_STALE(HttpStatus.CONFLICT, "근무표가 바뀌었어요. 미리보기를 다시 확인해 주세요."),
    FUTURE_SHIFT_REMAINS(HttpStatus.CONFLICT, "예정되거나 진행 중인 근무를 먼저 정리해 주세요."),
    ONBOARDING_STAGE_CONFLICT(HttpStatus.CONFLICT, "현재 가입 단계에서 할 수 없는 작업이에요."),
    ALREADY_IN_STORE(HttpStatus.CONFLICT, "이미 매장에 소속되어 있어요."),
    SHIFT_STATE_CONFLICT(HttpStatus.CONFLICT, "근무 정보가 바뀌었어요. 최신 근무표를 확인해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "같은 요청 키가 다른 내용으로 다시 사용되었어요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.", true);

    private final HttpStatus status;
    private final String defaultMessage;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this(status, defaultMessage, false);
    }

    ErrorCode(HttpStatus status, String defaultMessage, boolean retryable) {
        this.status = status;
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public String getCode() {
        return name();
    }
}
