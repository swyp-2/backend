package com.baton.integration.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ShiftAssignment;
import com.baton.integration.ShiftSnapshot;
import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.shift.application.ShiftAssignmentService;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A 구현이 없을 때 등록되는 ShiftAssignment 임시 구현의 계약 확인.
 *
 * <p>A의 실제 구현(ShiftAssignmentService)이 생겨 스텁은 더 이상 빈으로 등록되지 않는다. 스텁 계약은 유지하려고
 * 스텁 인스턴스를 직접 만들고, 빈일 때와 같은 트랜잭션 동작(@Transactional, MANDATORY 포함)을 프록시로 입힌다.
 * (2026-11-11 A 수정. 스텁 삭제 여부는 B가 결정)
 */
@IntegrationTest
class ShiftAssignmentStubTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(1));
    private static final Instant END = START.plus(Duration.ofHours(8));

    @Autowired ShiftAssignment injected;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    ShiftAssignment shiftAssignment;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private Long storeId;
    private Long fromId;
    private Long toId;
    private Long shiftId;

    @BeforeEach
    void setUp() {
        ProxyFactory proxyFactory = new ProxyFactory(new ShiftAssignmentStub(entityManager));
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        shiftAssignment = (ShiftAssignment) proxyFactory.getProxy();
        storeId = storeRepository.saveAndFlush(Store.open("스텁매장", NOW)).getId();
        fromId = newMember();
        toId = newMember();
        shiftId = shiftRepository.saveAndFlush(Shift.schedule(storeId, fromId, START, END, NOW)).getId();
    }

    @Test
    void A_구현이_있으면_스텁_대신_실제_구현이_주입된다() {
        assertThat(injected).isInstanceOf(ShiftAssignmentService.class);
    }

    @Test
    void 스냅샷은_현재_근무_상태를_돌려준다() {
        ShiftSnapshot snapshot = shiftAssignment.getSnapshot(shiftId);

        assertThat(snapshot).isEqualTo(new ShiftSnapshot(shiftId, storeId, fromId, START, END, 0L, false));
    }

    @Test
    void 삭제된_근무도_deleted_true로_돌려준다() {
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.delete(NOW);
        shiftRepository.saveAndFlush(shift);

        ShiftSnapshot snapshot = shiftAssignment.getSnapshot(shiftId);

        assertThat(snapshot.deleted()).isTrue();
        assertThat(snapshot.version()).isEqualTo(1L);
    }

    @Test
    void 없는_근무는_NOT_FOUND() {
        assertError(() -> shiftAssignment.getSnapshot(Long.MAX_VALUE), ErrorCode.NOT_FOUND);
    }

    @Test
    void 담당자를_바꾸고_버전을_올린다() {
        transactionTemplate.executeWithoutResult(status -> shiftAssignment.reassign(shiftId, fromId, toId, 0L));

        ShiftSnapshot snapshot = shiftAssignment.getSnapshot(shiftId);
        assertThat(snapshot.assigneeMembershipId()).isEqualTo(toId);
        assertThat(snapshot.version()).isEqualTo(1L);
    }

    @Test
    void 버전이_다르면_SHIFT_STATE_CONFLICT() {
        assertError(() -> transactionTemplate.executeWithoutResult(
                status -> shiftAssignment.reassign(shiftId, fromId, toId, 5L)), ErrorCode.SHIFT_STATE_CONFLICT);
        assertThat(shiftAssignment.getSnapshot(shiftId).assigneeMembershipId()).isEqualTo(fromId);
    }

    @Test
    void 현재_담당자가_from이_아니면_SHIFT_STATE_CONFLICT() {
        assertError(() -> transactionTemplate.executeWithoutResult(
                status -> shiftAssignment.reassign(shiftId, toId, fromId, 0L)), ErrorCode.SHIFT_STATE_CONFLICT);
    }

    @Test
    void 잠금_스냅샷은_같은_트랜잭션에서_먼저_읽은_뒤에도_최신_값을_돌려준다() {
        ShiftSnapshot locked = transactionTemplate.execute(status -> {
            shiftAssignment.getSnapshot(shiftId);
            // 그 사이 다른 트랜잭션에서 근무 삭제가 커밋된 상황
            TransactionTemplate requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew.executeWithoutResult(inner -> {
                Shift shift = shiftRepository.findById(shiftId).orElseThrow();
                shift.delete(NOW);
                shiftRepository.saveAndFlush(shift);
            });
            return shiftAssignment.lockSnapshot(shiftId);
        });

        assertThat(locked.deleted()).isTrue();
        assertThat(locked.version()).isEqualTo(1L);
    }

    @Test
    void 없는_근무를_잠그면_NOT_FOUND() {
        assertError(() -> transactionTemplate.execute(status -> shiftAssignment.lockSnapshot(Long.MAX_VALUE)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void 호출자_트랜잭션_없이_lockSnapshot하면_예외() {
        assertThatThrownBy(() -> shiftAssignment.lockSnapshot(shiftId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 호출자_트랜잭션_없이_reassign하면_예외() {
        assertThatThrownBy(() -> shiftAssignment.reassign(shiftId, fromId, toId, 0L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Long newMember() {
        Long userId = userRepository.saveAndFlush(User.signUp()).getId();
        return membershipRepository.saveAndFlush(Membership.join(storeId, userId, Role.CREW, "#112233", NOW)).getId();
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
