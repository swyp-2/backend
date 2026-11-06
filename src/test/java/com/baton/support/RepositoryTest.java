package com.baton.support;

import com.baton.config.ClockConfig;
import com.baton.config.JpaAuditingConfig;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 리포지토리·스키마 제약 테스트용: JPA 슬라이스 + Flyway 마이그레이션 + Testcontainers PostgreSQL.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, ClockConfig.class, JpaAuditingConfig.class})
public @interface RepositoryTest {
}
