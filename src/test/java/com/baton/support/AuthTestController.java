package com.baton.support;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 필터 테스트 전용 보호 경로. 테스트 소스에만 존재한다.
 */
@RestController
class AuthTestController {

    @GetMapping("/test-auth/whoami")
    AuthUser whoami(@CurrentUser AuthUser authUser) {
        return authUser;
    }
}
