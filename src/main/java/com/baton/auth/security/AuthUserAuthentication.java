package com.baton.auth.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * 인증 완료 상태의 Authentication. 역할 권한은 매장 소속마다 다르므로 GrantedAuthority를 두지 않는다.
 */
public class AuthUserAuthentication extends AbstractAuthenticationToken {

    private final AuthUser authUser;

    public AuthUserAuthentication(AuthUser authUser) {
        super(List.of());
        this.authUser = authUser;
        setAuthenticated(true);
    }

    @Override
    public AuthUser getPrincipal() {
        return authUser;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
