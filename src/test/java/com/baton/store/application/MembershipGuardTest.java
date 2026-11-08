package com.baton.store.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.IntegrationTest;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class MembershipGuardTest {

    @Autowired MembershipGuard guard;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    @Test
    void 활성_소속이면_통과하고_아니면_FORBIDDEN() {
        long storeId = newStore();
        long crew = newNamedUser("크루원");
        Membership membership = join(storeId, crew, Role.CREW);

        assertThat(guard.requireActive(crew, storeId))
                .isEqualTo(new ActiveMembership(membership.getId(), storeId, crew, "CREW"));
        assertForbidden(() -> guard.requireActive(crew, newStore()));

        membership.leave(clock.instant());
        membershipRepository.saveAndFlush(membership);
        assertForbidden(() -> guard.requireActive(crew, storeId));
    }

    @Test
    void 매니저가_아니면_FORBIDDEN() {
        long storeId = newStore();
        long manager = newNamedUser("매니저");
        long crew = newNamedUser("크루원");
        join(storeId, manager, Role.MANAGER);
        join(storeId, crew, Role.CREW);

        assertThat(guard.requireManager(manager, storeId).role()).isEqualTo("MANAGER");
        assertForbidden(() -> guard.requireManager(crew, storeId));
    }

    @Test
    void 사용자의_활성_소속을_찾는다() {
        long storeId = newStore();
        long user = newNamedUser("크루원");
        long noStore = newNamedUser("무소속");
        Membership membership = join(storeId, user, Role.CREW);

        assertThat(guard.findActiveByUser(user)).map(ActiveMembership::membershipId).contains(membership.getId());
        assertThat(guard.findActiveByUser(noStore)).isEmpty();
    }

    @Test
    void 대타_후보는_같은_매장_활성_크루원에서_본인을_뺀다() {
        long storeId = newStore();
        Membership requester = join(storeId, newNamedUser("요청자"), Role.CREW);
        Membership candidate = join(storeId, newNamedUser("후보"), Role.CREW);
        join(storeId, newNamedUser("매니저"), Role.MANAGER);
        Membership left = join(storeId, newNamedUser("나간사람"), Role.CREW);
        left.leave(clock.instant());
        membershipRepository.saveAndFlush(left);
        join(newStore(), newNamedUser("다른매장"), Role.CREW);

        assertThat(guard.findCrewCandidates(storeId, requester.getId()))
                .extracting(ActiveMembership::membershipId)
                .containsExactly(candidate.getId());
    }

    @Test
    void 잠금_조회는_트랜잭션_안에서만_되고_활성_소속만_돌려준다() {
        long storeId = newStore();
        Membership active = join(storeId, newNamedUser("활성"), Role.CREW);
        Membership left = join(storeId, newNamedUser("나간사람"), Role.CREW);
        left.leave(clock.instant());
        membershipRepository.saveAndFlush(left);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Optional<ActiveMembership> locked = tx.execute(status -> guard.lockActiveById(active.getId()));
        Optional<ActiveMembership> notActive = tx.execute(status -> guard.lockActiveById(left.getId()));

        assertThat(locked).map(ActiveMembership::membershipId).contains(active.getId());
        assertThat(notActive).isEmpty();
        assertThatThrownBy(() -> guard.lockActiveById(active.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 표시_이름은_탈퇴했거나_연결이_끊기면_탈퇴한_구성원() {
        long storeId = newStore();
        long user = newNamedUser("홍길동");
        Membership membership = join(storeId, user, Role.CREW);
        assertThat(guard.displayNameOf(membership.getId())).isEqualTo("홍길동");

        jdbcTemplate.update("update users set account_state = 'WITHDRAWN', withdrawn_at = now() where id = ?", user);
        assertThat(guard.displayNameOf(membership.getId())).isEqualTo(MembershipGuard.WITHDRAWN_MEMBER_NAME);

        Membership unlinked = join(storeId, newNamedUser("김철수"), Role.CREW);
        unlinked.withdraw(clock.instant());
        membershipRepository.saveAndFlush(unlinked);
        assertThat(guard.displayNameOf(unlinked.getId())).isEqualTo(MembershipGuard.WITHDRAWN_MEMBER_NAME);
    }

    @Test
    void 없는_소속의_표시_이름은_NOT_FOUND() {
        assertThatThrownBy(() -> guard.displayNameOf(Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    private long newStore() {
        return storeRepository.saveAndFlush(Store.open("가드테스트", clock.instant())).getId();
    }

    private long newNamedUser(String name) {
        long id = userRepository.saveAndFlush(User.signUp()).getId();
        jdbcTemplate.update("update users set display_name = ? where id = ?", name, id);
        return id;
    }

    private Membership join(long storeId, long userId, Role role) {
        return membershipRepository.saveAndFlush(Membership.join(storeId, userId, role, "#AABBCC", clock.instant()));
    }
}
