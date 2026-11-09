package com.baton.config;

import com.baton.auth.application.AccessTokenService;
import com.baton.auth.security.SessionCheckingJwtConverter;
import com.baton.auth.security.TestUserAuthenticationFilter;
import com.baton.config.security.JsonAccessDeniedHandler;
import com.baton.config.security.JsonAuthenticationEntryPoint;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 자체 액세스 토큰(HS256 JWT) 리소스 서버. 서명·만료 검증 후 SessionCheckingJwtConverter가
 * 세션 폐기·계정 탈퇴를 DB로 확인한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, CorsProperties.class})
public class SecurityConfig {

    /**
     * 로그인 전 호출하는 인증 API. 만료된 Authorization 헤더가 붙어 와도 무시한다.
     * 제공자 판정은 컨트롤러가 하므로 지원하지 않는 제공자도 토큰 유무와 관계없이 항상 404가 된다.
     */
    private static final String TOKEN_ISSUING_PATTERN = "/auth/*/complete";
    private static final Pattern TOKEN_ISSUING_PATH = Pattern.compile("^/auth/[^/]+/complete$");

    private static final String[] DOC_PATHS = {
            "/health",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AccessTokenService accessTokenService,
                                                   SessionCheckingJwtConverter sessionCheckingJwtConverter,
                                                   JsonAuthenticationEntryPoint authenticationEntryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler,
                                                   ObjectProvider<TestUserAuthenticationFilter> testUserFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(TOKEN_ISSUING_PATTERN).permitAll()
                        .requestMatchers(DOC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .decoder(accessTokenService.decoder())
                                .jwtAuthenticationConverter(sessionCheckingJwtConverter)))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        // local·test 프로필에만 존재
        testUserFilter.ifAvailable(filter -> http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (corsProperties.allowedOrigins().isEmpty()) {
            return source;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> TOKEN_ISSUING_PATH.matcher(request.getRequestURI()).matches()
                ? null : delegate.resolve(request);
    }
}
