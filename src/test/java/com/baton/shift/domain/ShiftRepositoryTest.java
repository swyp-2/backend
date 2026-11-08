package com.baton.shift.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baton.member.domain.Role;
import com.baton.member.domain.User;
import com.baton.member.domain.UserRepository;
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

@RepositoryTest
class ShiftRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T01:00:00Z");
    private static final Instant START = Instant.parse("2026-11-10T13:00:00Z");   // KST 22:00
    private static final Instant END = START.plus(Duration.ofHours(8));          // KST 익일 06:00

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MembershipRepository membershipRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ShiftEventRepository shiftEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long storeId;
    private Long crewId;

    @BeforeEach
    void setUp() {
        storeId = storeRepository.saveAndFlush(Store.open("테스트매장", NOW)).getId();
        crewId = newMember(storeId);
    }

    @Test
    void 같은_매장_담당자_시간의_유효_근무는_중복_저장되지_않는다() {
        shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW));

        assertThatThrownBy(() -> shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("shifts_identical_live_uk");
    }

    @Test
    void 삭제된_근무와_같은_시간은_다시_저장된다() {
        Shift deleted = shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW));
        deleted.delete(NOW);
        shiftRepository.saveAndFlush(deleted);

        Shift again = shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW));

        assertThat(again.getId()).isNotEqualTo(deleted.getId());
        assertThat(deleted.getVersion()).isEqualTo(1L);
    }

    @Test
    void 같은_시간이라도_담당자가_다르면_저장된다() {
        Long otherCrewId = newMember(storeId);
        shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW));

        shiftRepository.saveAndFlush(Shift.schedule(storeId, otherCrewId, START, END, NOW));

        assertThat(shiftRepository.count()).isEqualTo(2);
    }

    @Test
    void 정확히_24시간_근무는_check_제약으로_막힌다() {
        assertThatThrownBy(() -> shiftRepository.saveAndFlush(
                Shift.schedule(storeId, crewId, START, START.plus(Duration.ofHours(24)), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("shifts_duration_ck");
    }

    @Test
    void 종료가_시작보다_늦지_않으면_check_제약으로_막힌다() {
        assertThatThrownBy(() -> shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, START, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("shifts_duration_ck");
    }

    @Test
    void 다른_매장_소속을_담당자로_지정할_수_없다() {
        Long otherStoreId = storeRepository.saveAndFlush(Store.open("다른매장", NOW)).getId();
        Long outsiderId = newMember(otherStoreId);

        assertThatThrownBy(() -> shiftRepository.saveAndFlush(Shift.schedule(storeId, outsiderId, START, END, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("shifts_assignee_same_store_fk");
    }

    @Test
    void 근무_이벤트의_jsonb_값이_저장되고_조회된다() {
        Shift shift = shiftRepository.saveAndFlush(Shift.schedule(storeId, crewId, START, END, NOW));
        String after = "{\"assignee_membership_id\": " + crewId + "}";

        ShiftEvent saved = shiftEventRepository.saveAndFlush(
                ShiftEvent.record(shift.getId(), ShiftEventKind.CREATED, null, after, crewId, null, NOW));
        shiftEventRepository.flush();

        ShiftEvent found = shiftEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getAfterValue()).contains("\"assignee_membership_id\"");
        assertThat(found.getBeforeValue()).isNull();
        // 문자열 리터럴이 아닌 JSON 객체로 저장됐는지 확인
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_typeof(after_value) FROM shift_events WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("object");
    }

    private Long newMember(Long targetStoreId) {
        Long userId = userRepository.saveAndFlush(User.signUp()).getId();
        return membershipRepository.saveAndFlush(
                Membership.join(targetStoreId, userId, Role.CREW, "#112233", NOW)).getId();
    }
}
