package com.baton.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * API 통합 테스트용: 전체 컨텍스트 + Testcontainers PostgreSQL + MockMvc.
 * 소셜 ID 토큰 검증은 {@link FakeSocialTokenVerifier}로 교체된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FakeSocialAuthConfig.class})
public @interface IntegrationTest {
}
