package com.baton.auth.api;

import com.baton.auth.application.SessionService;
import com.baton.auth.application.SocialLoginService;
import com.baton.auth.domain.SocialProvider;
import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
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

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SocialLoginService socialLoginService;
    private final SessionService sessionService;
    private final Clock clock;

    /** CO-01: Google/Apple SDK가 준 ID 토큰으로 로그인(처음이면 가입 시작). */
    @PostMapping("/{provider}/complete")
    public ApiResponse<TokenResponse> complete(@PathVariable String provider,
                                               @Valid @RequestBody SocialCompleteRequest request) {
        return ApiResponse.ok(
                TokenResponse.from(socialLoginService.complete(parseProvider(provider), request.idToken())), clock);
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(TokenResponse.from(sessionService.refresh(request.refreshToken())), clock);
    }

    /** PR-04: 현재 세션만 종료한다. 계정·매장 소속은 유지된다. */
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
