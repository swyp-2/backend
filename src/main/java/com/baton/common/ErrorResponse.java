package com.baton.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ErrorResponse(
        ErrorBody error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currentState,
        /** ACTIVE_REQUEST_EXISTS일 때 이미 진행 중인 요청 id. 그 밖에는 응답에 나오지 않는다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Long existingRequestId
) {

    public record ErrorBody(String code, String message, List<FieldError> fieldErrors, boolean retryable) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, null, List.of(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
                                   String currentState) {
        return of(errorCode, message, fieldErrors, currentState, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
                                   String currentState, Long existingRequestId) {
        return new ErrorResponse(
                new ErrorBody(
                        errorCode.getCode(),
                        message != null ? message : errorCode.getDefaultMessage(),
                        fieldErrors != null ? List.copyOf(fieldErrors) : List.of(),
                        errorCode.isRetryable()),
                currentState,
                existingRequestId);
    }
}
