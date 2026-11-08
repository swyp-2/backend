package com.baton.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record ErrorResponse(
        ErrorBody error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String currentState
) {

    public record ErrorBody(String code, String message, List<FieldError> fieldErrors, boolean retryable) {
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, null, List.of(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
                                   String currentState) {
        return new ErrorResponse(
                new ErrorBody(
                        errorCode.getCode(),
                        message != null ? message : errorCode.getDefaultMessage(),
                        fieldErrors != null ? List.copyOf(fieldErrors) : List.of(),
                        errorCode.isRetryable()),
                currentState);
    }
}
