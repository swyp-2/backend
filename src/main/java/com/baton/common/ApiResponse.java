package com.baton.common;

import java.time.Clock;
import java.time.Instant;

public record ApiResponse<T>(T data, Meta meta) {

    public record Meta(Instant serverTime) {
    }

    public static <T> ApiResponse<T> ok(T data, Clock clock) {
        return new ApiResponse<>(data, new Meta(Instant.now(clock)));
    }
}
