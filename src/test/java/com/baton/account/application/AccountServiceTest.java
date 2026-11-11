package com.baton.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baton.auth.domain.AuthSessionRepository;
import com.baton.auth.domain.SocialAccountRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.idempotency.IdempotencyRecordRepository;
import com.baton.integration.SubstituteRequestLifecycle;
import com.baton.member.domain.AccountState;
import com.baton.member.domain.OnboardingStage;
import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserConsentRepository;
import com.baton.member.domain.UserRepository;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.MembershipState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final long USER_ID = 7L;
    private static final long STORE_ID = 3L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final MembershipRepository membershipRepository = mock(MembershipRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final AuthSessionRepository authSessionRepository = mock(AuthSessionRepository.class);
    private final SocialAccountRepository socialAccountRepository = mock(SocialAccountRepository.class);
    private final UserConsentRepository userConsentRepository = mock(UserConsentRepository.class);
    private final IdempotencyRecordRepository idempotencyRecordRepository = mock(IdempotencyRecordRepository.class);
    private final SubstituteRequestLifecycle lifecycle = mock(SubstituteRequestLifecycle.class);

    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepository, membershipRepository, shiftRepository, authSessionRepository,
                socialAccountRepository, userConsentRepository, idempotencyRecordRepository, lifecycle,
                Clock.fixed(NOW, ZoneOffset.UTC));
        user = User.signUp();
        user.agreeConsents();
        user.setName("하윤");
        user.selectRole(Role.CREW);
        user.joinStore();
        given(userRepository.findActiveByIdForUpdate(USER_ID)).willReturn(Optional.of(user));
    }

    // ---- 나가기 ----

    @Test
    void 끝나지_않은_근무가_있으면_FUTURE_SHIFT_REMAINS이고_아무것도_바꾸지_않는다() {
        Membership membership = membership(10L);
        given(membershipRepository.findActiveByStoreAndUserForUpdate(STORE_ID, USER_ID))
                .willReturn(Optional.of(membership));
        given(shiftRepository.existsUnfinishedByAssignee(10L, NOW)).willReturn(true);

        assertThatThrownBy(() -> service.leave(USER_ID, STORE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUTURE_SHIFT_REMAINS);
                    assertThat(e.getCurrentState()).isNull();
                });

        verifyNoInteractions(lifecycle);
        assertThat(membership.getState()).isEqualTo(MembershipState.ACTIVE);
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.COMPLETED);
    }

    @Test
    void 나가기는_잠금_검사_요청정리_소속해제_순서로_처리한다() {
        Membership membership = membership(10L);
        given(membershipRepository.findActiveByStoreAndUserForUpdate(STORE_ID, USER_ID))
                .willReturn(Optional.of(membership));

        service.leave(USER_ID, STORE_ID);

        InOrder order = inOrder(userRepository, membershipRepository, shiftRepository, lifecycle);
        order.verify(userRepository).findActiveByIdForUpdate(USER_ID);
        order.verify(membershipRepository).findActiveByStoreAndUserForUpdate(STORE_ID, USER_ID);
        order.verify(shiftRepository).existsUnfinishedByAssignee(10L, NOW);
        order.verify(lifecycle).cleanupOnMembershipLeave(10L);
        assertThat(membership.getState()).isEqualTo(MembershipState.LEFT);
        assertThat(membership.getLeftAt()).isEqualTo(NOW);
        assertThat(membership.getUserId()).isEqualTo(USER_ID);
        assertThat(user.getOnboardingStage()).isEqualTo(OnboardingStage.STORE_REQUIRED);
        assertThat(user.getSelectedRole()).isEqualTo(Role.CREW);
        assertThat(user.getDisplayName()).isEqualTo("하윤");
        verifyNoInteractions(authSessionRepository, socialAccountRepository, userConsentRepository);
    }

    @Test
    void 이_매장의_활성_소속이_없으면_403() {
        given(membershipRepository.findActiveByStoreAndUserForUpdate(STORE_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(USER_ID, STORE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(lifecycle);
    }

    // ---- 탈퇴 ----

    @Test
    void 탈퇴는_활성_소속_정리_후_모든_소속을_익명화하고_인증_정보를_지운다() {
        Membership left = membership(5L);
        left.leave(NOW.minusSeconds(3600));
        Membership active = membership(10L);
        given(membershipRepository.findAllByUserIdForUpdate(USER_ID)).willReturn(List.of(left, active));

        service.withdraw(USER_ID);

        InOrder order = inOrder(shiftRepository, lifecycle, idempotencyRecordRepository, authSessionRepository,
                socialAccountRepository, userConsentRepository);
        order.verify(shiftRepository).existsUnfinishedByAssignee(10L, NOW);
        order.verify(lifecycle).cleanupOnMembershipLeave(10L);
        order.verify(lifecycle).anonymizeFor(5L);
        order.verify(lifecycle).anonymizeFor(10L);
        order.verify(idempotencyRecordRepository).deleteByUserId(USER_ID);
        order.verify(authSessionRepository).deleteByUserId(USER_ID);
        order.verify(socialAccountRepository).deleteByUserId(USER_ID);
        order.verify(userConsentRepository).deleteByUserId(USER_ID);
        verify(shiftRepository, never()).existsUnfinishedByAssignee(5L, NOW);
        verify(lifecycle, never()).cleanupOnMembershipLeave(5L);

        assertThat(List.of(left, active)).allSatisfy(m -> {
            assertThat(m.getState()).isEqualTo(MembershipState.WITHDRAWN);
            assertThat(m.getUserId()).isNull();
        });
        assertThat(left.getLeftAt()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(active.getLeftAt()).isEqualTo(NOW);
        assertThat(user.getAccountState()).isEqualTo(AccountState.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isEqualTo(NOW);
        assertThat(user.getDisplayName()).isNull();
        assertThat(user.getSelectedRole()).isNull();
    }

    @Test
    void 탈퇴_시_끝나지_않은_근무가_있으면_아무것도_지우지_않는다() {
        Membership active = membership(10L);
        given(membershipRepository.findAllByUserIdForUpdate(USER_ID)).willReturn(List.of(active));
        given(shiftRepository.existsUnfinishedByAssignee(10L, NOW)).willReturn(true);

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FUTURE_SHIFT_REMAINS));

        verifyNoInteractions(lifecycle, authSessionRepository, socialAccountRepository, userConsentRepository,
                idempotencyRecordRepository);
        assertThat(active.getState()).isEqualTo(MembershipState.ACTIVE);
        assertThat(user.getAccountState()).isEqualTo(AccountState.ACTIVE);
    }

    @Test
    void 소속이_없는_회원의_탈퇴는_요청_정리_없이_계정만_파기한다() {
        given(membershipRepository.findAllByUserIdForUpdate(USER_ID)).willReturn(List.of());

        service.withdraw(USER_ID);

        verify(lifecycle, never()).cleanupOnMembershipLeave(anyLong());
        verify(lifecycle, never()).anonymizeFor(anyLong());
        verify(shiftRepository, never()).existsUnfinishedByAssignee(anyLong(), any());
        verify(authSessionRepository).deleteByUserId(USER_ID);
        assertThat(user.getAccountState()).isEqualTo(AccountState.WITHDRAWN);
    }

    @Test
    void 탈퇴했거나_없는_계정이면_UNAUTHENTICATED() {
        given(userRepository.findActiveByIdForUpdate(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(membershipRepository, lifecycle);
    }

    private static Membership membership(long id) {
        Membership membership = Membership.join(STORE_ID, USER_ID, Role.CREW, "#AABBCC", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(membership, "id", id);
        return membership;
    }
}
