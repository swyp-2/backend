package com.baton.substitute.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
import com.baton.notification.domain.Notification;
import com.baton.notification.domain.NotificationRepository;
import com.baton.shift.domain.Shift;
import com.baton.shift.domain.ShiftRepository;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipRepository;
import com.baton.store.domain.Store;
import com.baton.store.domain.StoreRepository;
import com.baton.support.RepositoryTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대타·알림 테이블의 DB 제약 확인. 서비스 검사가 빠져도 DB가 막아야 하는 규칙들.
 */
@RepositoryTest
class SubstituteSchemaTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final Instant START = NOW.plus(Duration.ofDays(1));
    private static final Instant END = START.plus(Duration.ofHours(8));

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SubstituteRequestRepository requestRepository;
    @Autowired RequestRecipientRepository recipientRepository;
    @Autowired RequestEventRepository eventRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long storeId;
    private Long requesterId;
    private Long targetId;
    private Long shiftId;

    @BeforeEach
    void setUp() {
        storeId = storeRepository.saveAndFlush(Store.open("테스트매장", NOW)).getId();
        requesterId = newMember(storeId);
        targetId = newMember(storeId);
        shiftId = shiftRepository.saveAndFlush(Shift.schedule(storeId, requesterId, START, END, NOW)).getId();
    }

    @Test
    void 같은_근무에_진행_중_요청은_한_건만_저장된다() {
        requestRepository.saveAndFlush(open());

        assertViolation(() -> requestRepository.saveAndFlush(open()), "substitute_requests_shift_active_uk");
    }

    @Test
    void 종료된_요청이_있어도_같은_근무에_새_요청을_만들_수_있다() {
        SubstituteRequest closed = requestRepository.saveAndFlush(open());
        closed.cancel(NOW);
        requestRepository.saveAndFlush(closed);

        SubstituteRequest again = requestRepository.saveAndFlush(open());

        assertThat(again.getId()).isNotEqualTo(closed.getId());
        assertThat(closed.getVersion()).isEqualTo(1L);
    }

    @Test
    void 상태_사유_후보_종료시각이_엔티티대로_저장된다() {
        SubstituteRequest request = requestRepository.saveAndFlush(open());
        request.accept(targetId, NOW);
        request.closeBySourceDeleted(NOW);
        requestRepository.saveAndFlush(request);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, terminal_reason, candidate_membership_id, closed_at IS NOT NULL AS closed "
                        + "FROM substitute_requests WHERE id = ?", request.getId()))
                .containsEntry("status", "CANCELLED_SOURCE_CHANGED")
                .containsEntry("terminal_reason", "SHIFT_DELETED")
                .containsEntry("candidate_membership_id", targetId)
                .containsEntry("closed", true);
    }

    @Test
    void WAITING인데_후보가_있으면_check_제약으로_막힌다() {
        Long id = requestRepository.saveAndFlush(open()).getId();

        assertViolation(() -> jdbcTemplate.update(
                "UPDATE substitute_requests SET candidate_membership_id = ? WHERE id = ?", targetId, id),
                "substitute_requests_candidate_ck");
    }

    @Test
    void 승인_대기인데_후보가_없으면_check_제약으로_막힌다() {
        Long id = requestRepository.saveAndFlush(open()).getId();

        assertViolation(() -> jdbcTemplate.update(
                "UPDATE substitute_requests SET status = 'PENDING_MANAGER' WHERE id = ?", id),
                "substitute_requests_candidate_ck");
    }

    @Test
    void 종료_상태인데_종료_시각이_없으면_check_제약으로_막힌다() {
        Long id = requestRepository.saveAndFlush(open()).getId();

        assertViolation(() -> jdbcTemplate.update(
                "UPDATE substitute_requests SET status = 'CANCELLED' WHERE id = ?", id),
                "substitute_requests_closed_at_ck");
    }

    @Test
    void 상태에_맞지_않는_종료_사유는_check_제약으로_막힌다() {
        Long id = requestRepository.saveAndFlush(open()).getId();

        assertViolation(() -> jdbcTemplate.update(
                "UPDATE substitute_requests SET status = 'CANCELLED', terminal_reason = 'SHIFT_DELETED', "
                        + "closed_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW), id),
                "substitute_requests_reason_ck");
    }

    @Test
    void 다른_매장_소속은_요청자가_될_수_없다() {
        Long otherStoreId = storeRepository.saveAndFlush(Store.open("다른매장", NOW)).getId();
        Long outsiderId = newMember(otherStoreId);

        assertViolation(() -> requestRepository.saveAndFlush(
                SubstituteRequest.open(storeId, shiftId, outsiderId, START, END, 0L, NOW)),
                "substitute_requests_requester_same_store_fk");
    }

    @Test
    void 같은_동료를_한_요청에_두_번_지정할_수_없다() {
        Long requestId = requestRepository.saveAndFlush(open()).getId();
        recipientRepository.saveAndFlush(RequestRecipient.designate(requestId, targetId));

        assertViolation(() -> recipientRepository.saveAndFlush(RequestRecipient.designate(requestId, targetId)),
                "request_recipients_request_target_uk");
    }

    @Test
    void 같은_이벤트_같은_수신자에게_알림은_한_건만_저장된다() {
        Long requestId = requestRepository.saveAndFlush(open()).getId();
        Long eventId = eventRepository.saveAndFlush(RequestEvent.record(requestId, requesterId,
                RequestEventKind.REQUEST_CREATED, null, SubstituteStatus.WAITING, NOW)).getId();
        notificationRepository.saveAndFlush(
                Notification.of(targetId, requestId, eventId, RequestEventKind.REQUEST_CREATED, NOW));

        assertViolation(() -> notificationRepository.saveAndFlush(
                Notification.of(targetId, requestId, eventId, RequestEventKind.REQUEST_CREATED, NOW)),
                "notifications_event_recipient_uk");
    }

    @Test
    void 알림_만료_시각은_생성_후_168시간이어야_한다() {
        Long requestId = requestRepository.saveAndFlush(open()).getId();
        Long eventId = eventRepository.saveAndFlush(RequestEvent.record(requestId, requesterId,
                RequestEventKind.REQUEST_CREATED, null, SubstituteStatus.WAITING, NOW)).getId();
        Long id = notificationRepository.saveAndFlush(
                Notification.of(targetId, requestId, eventId, RequestEventKind.REQUEST_CREATED, NOW)).getId();

        assertViolation(() -> jdbcTemplate.update(
                "UPDATE notifications SET expires_at = expires_at + interval '1 hour' WHERE id = ?", id),
                "notifications_expiry_ck");
    }

    private SubstituteRequest open() {
        return SubstituteRequest.open(storeId, shiftId, requesterId, START, END, 0L, NOW);
    }

    private Long newMember(Long targetStoreId) {
        Long userId = userRepository.saveAndFlush(User.signUp()).getId();
        return membershipRepository.saveAndFlush(
                Membership.join(targetStoreId, userId, Role.CREW, "#112233", NOW)).getId();
    }

    private static void assertViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                        String constraint) {
        assertThatThrownBy(call)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining(constraint);
    }
}
