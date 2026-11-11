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

    /** 어떤 테이블에도 없는 id. 테스트끼리 DB를 공유하므로 다른 테스트의 데이터를 건드리지 않게 한다. */
    private static final long NONE = -1L;

    @Autowired SubstituteRequestLifecycle lifecycle;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void 구현_빈이_주입된다() {
        assertThat(lifecycle).isInstanceOf(SubstituteRequestLifecycleService.class);
    }

    @Test
    void 트랜잭션_필수_메서드를_트랜잭션_없이_호출하면_예외() {
        assertThatThrownBy(() -> lifecycle.closeBySourceDeleted(NONE, NONE))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> lifecycle.cleanupOnMembershipLeave(NONE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 트랜잭션_필수_메서드를_호출자_트랜잭션_안에서_호출하면_정상() {
        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(lifecycle.closeBySourceDeleted(NONE, NONE)).isEmpty();
            lifecycle.cleanupOnMembershipLeave(NONE);
        })).doesNotThrowAnyException();
    }

    @Test
    void 조회와_파기는_트랜잭션_없이_호출해도_된다() {
        assertThat(lifecycle.findActiveByShift(NONE, NONE)).isEmpty();
        assertThatCode(() -> lifecycle.anonymizeFor(NONE)).doesNotThrowAnyException();
    }
}
