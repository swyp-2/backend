package com.baton.member.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.member.application.ConsentService;
import com.baton.member.application.MeService;
import com.baton.member.application.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 회원 정보와 가입 단계(CO-01~05). 저장 API는 갱신된 /me 응답(next_screen 포함)을 돌려준다.
 */
@Tag(name = "온보딩", description = "내 정보·약관 동의·이름·역할(가입 단계)")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;
    private final ConsentService consentService;
    private final OnboardingService onboardingService;
    private final Clock clock;

    /** 회원·가입 단계·선택 역할·현재 소속과 다음 화면. 소속 없는 계정도 허용한다. */
    @Operation(summary = "내 정보와 다음 화면 조회", description = """
            COM-01, PRO-01 / CO-01~CO-05, PR-01

            회원·가입 단계·선택 역할·현재 소속과 앱 진입 시 이동할 화면(next_screen)을 돌려준다.
            소속 없는 계정도 허용한다. 이메일·소셜 식별자·토큰은 포함하지 않는다.

            - 401 UNAUTHENTICATED: 토큰 없음·만료·위조·세션 종료·탈퇴
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<MeResponse> me(@CurrentUser AuthUser authUser) {
        return ok(MeResponse.from(meService.getMe(authUser.userId())));
    }

    /** CO-02: 문서 버전별 동의 기록 저장. CONSENT_REQUIRED → NAME_REQUIRED. */
    @Operation(summary = "약관 동의 저장", description = """
            COM-03 / CO-02

            현재 유효한 문서 전부에 대해 동의 여부를 기록한다(동의 시각은 서버 시각). 요청에 없는 선택 문서는 미동의로 기록한다.
            CONSENT_REQUIRED → NAME_REQUIRED. 응답은 갱신된 내 정보다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 409 ONBOARDING_STAGE_CONFLICT: CONSENT_REQUIRED 단계가 아님(이미 동의함 등). current_state에 현재 단계.
              GET /me 재조회 후 next_screen으로 이동한다
            - 422 VALIDATION_FAILED: agreements·document_id·agreed 누락, 같은 문서 중복, 현재 유효하지 않은 문서,
              필수 문서 미동의. field는 agreements[document_id=N] 형식. 본문을 읽을 수 없음(field: body)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 후 내 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "가입 단계 충돌",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/consents")
    public ApiResponse<MeResponse> agree(@CurrentUser AuthUser authUser,
                                         @Valid @RequestBody ConsentAgreeRequest request) {
        return ok(MeResponse.from(consentService.agree(authUser.userId(), request.toCommands())));
    }

    /** CO-04: 가입 이름 저장. NAME_REQUIRED → ROLE_REQUIRED. */
    @Operation(summary = "가입 이름 저장", description = """
            COM-03 / CO-04

            앞뒤 공백을 뺀 이름을 저장한다. NAME_REQUIRED → ROLE_REQUIRED. 매장 소속은 만들지 않는다.
            단계 검사가 이름 검증보다 먼저다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 409 ONBOARDING_STAGE_CONFLICT: NAME_REQUIRED 단계가 아님. current_state에 현재 단계
            - 422 VALIDATION_FAILED: 이름 누락, 앞뒤 공백 제거 후 2~15자 아님, 줄바꿈·제어문자 포함(field: display_name),
              본문을 읽을 수 없음(field: body)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 후 내 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "가입 단계 충돌",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/onboarding/name")
    public ApiResponse<MeResponse> setName(@CurrentUser AuthUser authUser, @RequestBody NameRequest request) {
        return ok(MeResponse.from(onboardingService.setName(authUser.userId(), request.displayName())));
    }

    /** CO-05: 가입 역할 저장. ROLE_REQUIRED → STORE_REQUIRED. 매장 권한은 생기지 않는다. */
    @Operation(summary = "가입 역할 저장", description = """
            COM-04 / CO-05

            MANAGER 또는 CREW를 저장한다. ROLE_REQUIRED → STORE_REQUIRED. 어떤 매장 권한도 생기지 않는다
            (매장 권한은 소속의 role로만 판정). 가입 후 역할 변경 API가 아니다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 409 ONBOARDING_STAGE_CONFLICT: ROLE_REQUIRED 단계가 아님, 또는 이미 활성 매장 소속이 있음.
              current_state에 현재 단계
            - 422 VALIDATION_FAILED: MANAGER·CREW 외 값·누락(field: role), 본문을 읽을 수 없음(field: body)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 후 내 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "가입 단계 충돌",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/onboarding/role")
    public ApiResponse<MeResponse> selectRole(@CurrentUser AuthUser authUser, @RequestBody RoleRequest request) {
        return ok(MeResponse.from(onboardingService.selectRole(authUser.userId(), request.role())));
    }

    private <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, clock);
    }
}
