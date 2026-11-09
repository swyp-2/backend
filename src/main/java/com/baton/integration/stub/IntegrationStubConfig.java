package com.baton.integration.stub;

import com.baton.integration.ShiftAssignment;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 상대 개발자 구현이 아직 없는 integration 인터페이스의 임시 빈.
 *
 * <p>{@code @Component}에 조건을 달면 스캔 순서에 따라 조건 판정이 어긋날 수 있어 {@code @Bean} 메서드로 등록한다.
 * {@code @Bean} 조건은 컴포넌트 스캔으로 실제 구현 빈이 모두 등록된 뒤 판정된다.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationStubConfig {

    /** A가 ShiftAssignment 구현(@Service 등)을 추가하면 이 빈은 등록되지 않는다. */
    @Bean
    @ConditionalOnMissingBean(ShiftAssignment.class)
    public ShiftAssignment shiftAssignmentStub(EntityManager entityManager) {
        return new ShiftAssignmentStub(entityManager);
    }
}
