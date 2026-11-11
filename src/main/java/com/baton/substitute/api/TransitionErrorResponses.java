package com.baton.substitute.api;

import com.baton.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 상태 전이 API 6개의 공통 응답 문서. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 후 최신 요청 상세"),
        @ApiResponse(responseCode = "403", description = "권한 없음(관계·역할·소속)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "없는 요청",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "요청·근무 상태 충돌, 시작 시각 지남. current_state에 최신 상태",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Idempotency-Key 누락·형식 오류·재사용",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@interface TransitionErrorResponses {
}
