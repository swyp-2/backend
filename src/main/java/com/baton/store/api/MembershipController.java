package com.baton.store.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.member.domain.Role;
import com.baton.store.application.StoreCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "매장")
@RestController
@RequestMapping("/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final StoreCommandService storeCommandService;
    private final Clock clock;

    /** ST-03: 초대 코드로 즉시 참여(승인 단계 없음). 같은 매장 재참여는 기존 소속을 200으로 돌려준다. */
    @Operation(summary = "초대 코드로 매장 참여", description = """
            STR-03 / ST-03

            유효한 코드면 승인 단계 없이 바로 CREW 소속을 만든다. 가입 단계 STORE_REQUIRED → COMPLETED.
            같은 매장에 이미 활성 소속이면 새로 만들지 않고 기존 소속을 200으로 돌려준다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 가입 역할이 CREW가 아님, 또는 매장 연결 단계(STORE_REQUIRED)가 아님
            - 409 ALREADY_IN_STORE: 다른 매장에 이미 활성 소속이 있음
            - 422 VALIDATION_FAILED: 코드 빈 값·영문 대문자·숫자 8자리 형식 아님(field: code), 본문을 읽을 수 없음(field: body)
            - 422 INVALID_INVITE_CODE: 형식은 맞지만 유효한 코드가 아님
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "참여한 매장"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "단계·역할 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "다른 매장 소속",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "코드 형식 오류·유효하지 않은 코드",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/join")
    public ApiResponse<JoinResponse> join(@CurrentUser AuthUser authUser, @RequestBody JoinRequest request) {
        StoreCommandService.JoinedStore joined = storeCommandService.join(authUser.userId(), request.code());
        return ApiResponse.ok(
                new JoinResponse(joined.storeId(), joined.storeName(), joined.membershipId(), joined.role()), clock);
    }

    public record JoinRequest(
            @Schema(description = "매장 공통 초대 코드. 영문 대문자·숫자 8자리(앞뒤 공백은 무시)", example = "BATON726",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String code
    ) {
    }

    public record JoinResponse(
            @Schema(description = "매장 id", example = "1")
            long storeId,
            @Schema(description = "매장명", example = "바톤 카페")
            String storeName,
            @Schema(description = "내 소속 id", example = "11")
            long membershipId,
            @Schema(description = "매장 안 역할", example = "CREW")
            Role role
    ) {
    }
}
