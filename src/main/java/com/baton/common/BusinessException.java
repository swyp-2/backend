package com.baton.common;

import java.util.List;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> fieldErrors;
    private final String currentState;
    /** ACTIVE_REQUEST_EXISTS일 때 이미 진행 중인 요청 id. 없으면 null. */
    private final Long existingRequestId;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, List.of(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public BusinessException(ErrorCode errorCode, List<FieldError> fieldErrors) {
        this(errorCode, null, fieldErrors, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors, String currentState) {
        this(errorCode, message, fieldErrors, currentState, null);
    }

    private BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors, String currentState,
                              Long existingRequestId) {
        super(message != null ? message : errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors != null ? List.copyOf(fieldErrors) : List.of();
        this.currentState = currentState;
        this.existingRequestId = existingRequestId;
    }

    public static BusinessException withCurrentState(ErrorCode errorCode, String currentState) {
        return new BusinessException(errorCode, null, List.of(), currentState);
    }

    /** 같은 근무에 진행 중 요청이 이미 있음(409). 프론트는 existing_request_id의 상세로 이동한다. */
    public static BusinessException activeRequestExists(Long existingRequestId) {
        return new BusinessException(ErrorCode.ACTIVE_REQUEST_EXISTS, null, List.of(), null, existingRequestId);
    }

    public ErrorResponse toResponse() {
        return ErrorResponse.of(errorCode, getMessage(), fieldErrors, currentState, existingRequestId);
    }
}
