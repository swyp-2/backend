package com.baton.substitute.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A가 호출하는 계약 확인: 빈이 하나로 주입되고, 트랜잭션 필수 메서드는 호출자 트랜잭션을 요구한다.
 */
@IntegrationTest
class SubstituteRequestLifecycleContractTest {

    @Autowired SubstituteRequestLifecycle lifecycle;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void 구현_빈이_주입된다() {
        assertThat(lifecycle).isInstanceOf(SubstituteRequestLifecycleService.class);
    }

    @Test
    void 트랜잭션_필수_메서드를_트랜잭션_없이_호출하면_예외() {
        assertThatThrownBy(() -> lifecycle.closeBySourceDeleted(1L, 2L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> lifecycle.cleanupOnMembershipLeave(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 트랜잭션_필수_메서드를_호출자_트랜잭션_안에서_호출하면_정상() {
        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(lifecycle.closeBySourceDeleted(1L, 2L)).isEmpty();
            lifecycle.cleanupOnMembershipLeave(1L);
        })).doesNotThrowAnyException();
    }

    @Test
    void 조회와_파기는_트랜잭션_없이_호출해도_된다() {
        assertThat(lifecycle.findActiveByShift(1L, 2L)).isEmpty();
        assertThatCode(() -> lifecycle.anonymizeFor(1L)).doesNotThrowAnyException();
    }
}
