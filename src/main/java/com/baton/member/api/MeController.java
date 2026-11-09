package com.baton.member.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.member.application.ConsentService;
import com.baton.member.application.MeService;
import com.baton.member.application.OnboardingService;
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
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;
    private final ConsentService consentService;
    private final OnboardingService onboardingService;
    private final Clock clock;

    /** 회원·가입 단계·선택 역할·현재 소속과 다음 화면. 소속 없는 계정도 허용한다. */
    @GetMapping
    public ApiResponse<MeResponse> me(@CurrentUser AuthUser authUser) {
        return ok(MeResponse.from(meService.getMe(authUser.userId())));
    }

    /** CO-02: 문서 버전별 동의 기록 저장. CONSENT_REQUIRED → NAME_REQUIRED. */
    @PostMapping("/consents")
    public ApiResponse<MeResponse> agree(@CurrentUser AuthUser authUser,
                                         @Valid @RequestBody ConsentAgreeRequest request) {
        return ok(MeResponse.from(consentService.agree(authUser.userId(), request.toCommands())));
    }

    /** CO-04: 가입 이름 저장. NAME_REQUIRED → ROLE_REQUIRED. */
    @PutMapping("/onboarding/name")
    public ApiResponse<MeResponse> setName(@CurrentUser AuthUser authUser, @RequestBody NameRequest request) {
        return ok(MeResponse.from(onboardingService.setName(authUser.userId(), request.displayName())));
    }

    /** CO-05: 가입 역할 저장. ROLE_REQUIRED → STORE_REQUIRED. 매장 권한은 생기지 않는다. */
    @PutMapping("/onboarding/role")
    public ApiResponse<MeResponse> selectRole(@CurrentUser AuthUser authUser, @RequestBody RoleRequest request) {
        return ok(MeResponse.from(onboardingService.selectRole(authUser.userId(), request.role())));
    }

    private <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, clock);
    }
}
