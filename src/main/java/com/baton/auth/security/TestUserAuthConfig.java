package com.baton.auth.security;

import com.baton.config.security.JsonAuthenticationEntryPoint;
import com.baton.member.domain.UserRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * X-Test-User-Id 인증은 local·test에서만 존재한다. prod에는 이 빈들이 아예 없다.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
public class TestUserAuthConfig {

    @Bean
    public TestUserAuthenticationFilter testUserAuthenticationFilter(UserRepository userRepository,
                                                                     JsonAuthenticationEntryPoint entryPoint) {
        return new TestUserAuthenticationFilter(userRepository, entryPoint);
    }

    /** 시큐리티 체인에서만 실행되도록 서블릿 필터 자동 등록을 끈다. */
    @Bean
    public FilterRegistrationBean<TestUserAuthenticationFilter> testUserAuthenticationFilterRegistration(
            TestUserAuthenticationFilter filter) {
        FilterRegistrationBean<TestUserAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
