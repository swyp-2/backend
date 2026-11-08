package com.baton.store.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.support.RepositoryTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@RepositoryTest
class MembershipRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T01:00:00Z");

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired InviteCodeRepository inviteCodeRepository;

    @Test
    void 같은_매장에_활성_소속이_중복되면_DB가_거부한다() {
        Long userId = newUser();
        Long storeId = newStore();
        membershipRepository.saveAndFlush(Membership.join(storeId, userId, Role.CREW, "#AABBCC", NOW));

        assertThatThrownBy(() ->
                membershipRepository.saveAndFlush(Membership.join(storeId, userId, Role.CREW, "#AABBCC", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("memberships_");
    }

    @Test
    void 계정당_활성_매장은_하나다() {
        Long userId = newUser();
        membershipRepository.saveAndFlush(Membership.join(newStore(), userId, Role.MANAGER, "#AABBCC", NOW));

        assertThatThrownBy(() ->
                membershipRepository.saveAndFlush(Membership.join(newStore(), userId, Role.CREW, "#AABBCC", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("memberships_user_active_uk");
    }

    @Test
    void 나간_뒤에는_같은_매장에_다시_참여할_수_있다() {
        Long userId = newUser();
        Long storeId = newStore();
        Membership first = membershipRepository.saveAndFlush(Membership.join(storeId, userId, Role.CREW, "#AABBCC", NOW));
        first.leave(NOW);
        membershipRepository.saveAndFlush(first);

        Membership rejoined = membershipRepository.saveAndFlush(
                Membership.join(storeId, userId, Role.CREW, "#AABBCC", NOW.plusSeconds(60)));

        assertThat(rejoined.getId()).isNotEqualTo(first.getId());
        assertThat(first.getState()).isEqualTo(MembershipState.LEFT);
    }

    @Test
    void 탈퇴하면_개인_연결이_끊긴_채로_저장된다() {
        Membership membership = membershipRepository.saveAndFlush(
                Membership.join(newStore(), newUser(), Role.CREW, "#AABBCC", NOW));

        membership.withdraw(NOW);
        membershipRepository.saveAndFlush(membership);

        assertThat(membership.getUserId()).isNull();
        assertThat(membership.getState()).isEqualTo(MembershipState.WITHDRAWN);
    }

    @Test
    void 매장별_유효_초대코드는_하나다() {
        Long storeId = newStore();
        inviteCodeRepository.saveAndFlush(InviteCode.issue(storeId, "ABCD1234", NOW));

        assertThatThrownBy(() -> inviteCodeRepository.saveAndFlush(InviteCode.issue(storeId, "WXYZ9876", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("invite_codes_store_valid_uk");
    }

    @Test
    void 초대코드는_영문_대문자와_숫자_8자리만_허용한다() {
        Long storeId = newStore();

        assertThatThrownBy(() -> inviteCodeRepository.saveAndFlush(InviteCode.issue(storeId, "abcd1234", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("invite_codes_code_format_ck");
    }

    private Long newUser() {
        return userRepository.saveAndFlush(User.signUp()).getId();
    }

    private Long newStore() {
        return storeRepository.saveAndFlush(Store.open("테스트매장", NOW)).getId();
    }
}
