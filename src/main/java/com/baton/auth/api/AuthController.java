package com.baton.auth.api;

import com.baton.auth.application.SessionService;
import com.baton.auth.application.SocialLoginService;
import com.baton.auth.domain.SocialProvider;
import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "소셜 로그인·로그아웃")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SocialLoginService socialLoginService;
    private final SessionService sessionService;
    private final Clock clock;

    /** CO-01: Google/Apple SDK가 준 ID 토큰으로 로그인(처음이면 가입 시작). */
    @Operation(summary = "소셜 로그인 완료", description = """
            COM-02 / CO-01

            Google·Apple SDK가 준 ID 토큰을 검증하고 BATON 액세스 토큰을 발급한다. 처음 로그인하면 회원을 만든다
            (onboarding_stage=CONSENT_REQUIRED). refresh 토큰은 없고, 만료되면 다시 소셜 로그인한다.
            인증 없이 호출한다(Authorization 헤더가 붙어 와도 무시).

            - 401 UNAUTHENTICATED: ID 토큰 서명·iss·aud·exp·sub 검증 실패, 제공자 설정 없음, 활성 계정이 아님
            - 404 NOT_FOUND: 지원하지 않는 provider(google·apple 외)
            - 422 VALIDATION_FAILED: id_token 누락·빈 값·8192자 초과, 본문을 읽을 수 없음
            - 500 INTERNAL_ERROR: 제공자 공개키(JWKS) 조회 실패. retryable=true
            """)
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "ID 토큰 검증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "지원하지 않는 제공자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500",
                    description = "제공자 공개키 조회 실패(재시도 가능)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{provider}/complete")
    public ApiResponse<TokenResponse> complete(
            @Parameter(description = "소셜 로그인 제공자", example = "google",
                    schema = @Schema(allowableValues = {"google", "apple"}))
            @PathVariable String provider,
            @Valid @RequestBody SocialCompleteRequest request) {
        return ApiResponse.ok(
                TokenResponse.from(socialLoginService.complete(parseProvider(provider), request.idToken())), clock);
    }

    /** PR-04: 현재 세션만 종료한다. 계정·매장 소속은 유지된다. */
    @Operation(summary = "로그아웃", description = """
            PRO-03 / PR-04

            현재 세션만 종료한다. 이 토큰은 이후 401이 되고, 계정·매장 소속과 다른 기기의 세션은 유지된다.

            - 401 UNAUTHENTICATED: 토큰 없음·만료·위조·이미 종료된 세션
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "로그아웃됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CurrentUser AuthUser authUser) {
        sessionService.logout(authUser);
    }

    private static SocialProvider parseProvider(String provider) {
        return switch (provider) {
            case "google" -> SocialProvider.GOOGLE;
            case "apple" -> SocialProvider.APPLE;
            default -> throw new BusinessException(ErrorCode.NOT_FOUND);
        };
    }
}
