package com.baton.auth.security;

import com.baton.member.domain.AccountState;
import com.baton.member.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * local·test 프로필 전용. X-Test-User-Id 헤더의 사용자로 인증한다. 빈 등록은 {@link TestUserAuthConfig}에서만 한다.
 */
public class TestUserAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Test-User-Id";

    private final UserRepository userRepository;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public TestUserAuthenticationFilter(UserRepository userRepository, AuthenticationEntryPoint entryPoint) {
        this.userRepository = userRepository;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Long userId = parse(header);
        if (userId == null || !userRepository.existsByIdAndAccountState(userId, AccountState.ACTIVE)) {
            contextHolderStrategy.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("테스트 사용자 인증 실패"));
            return;
        }
        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(new AuthUserAuthentication(new AuthUser(userId, null)));
        contextHolderStrategy.setContext(context);
        chain.doFilter(request, response);
    }

    private static Long parse(String header) {
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
