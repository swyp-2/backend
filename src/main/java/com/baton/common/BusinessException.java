package com.baton.common;

import java.util.List;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> fieldErrors;
    private final String currentState;

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
        super(message != null ? message : errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors != null ? List.copyOf(fieldErrors) : List.of();
        this.currentState = currentState;
    }

    public static BusinessException withCurrentState(ErrorCode errorCode, String currentState) {
        return new BusinessException(errorCode, null, List.of(), currentState);
    }

    public ErrorResponse toResponse() {
        return ErrorResponse.of(errorCode, getMessage(), fieldErrors, currentState);
    }
}
