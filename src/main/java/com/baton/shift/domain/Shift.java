package com.baton.shift.domain;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 근무 한 건. 수정 API는 없고 삭제(소프트)와 승인에 따른 담당자 변경만 허용한다.
 * 상태 변경 기록(ShiftEvent)은 서비스가 남긴다.
 */
@Getter
@Entity
@Table(name = "shifts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "shifts_seq")
    @SequenceGenerator(name = "shifts_seq", sequenceName = "shifts_seq", allocationSize = 50)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "assignee_membership_id", nullable = false)
    private Long assigneeMembershipId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 길이(0분 초과·24시간 미만)와 중복은 호출 측 검증 + DB 제약으로 보장한다. */
    public static Shift schedule(Long storeId, Long assigneeMembershipId, Instant startsAt, Instant endsAt, Instant now) {
        Shift shift = new Shift();
        shift.storeId = storeId;
        shift.assigneeMembershipId = assigneeMembershipId;
        shift.startsAt = startsAt;
        shift.endsAt = endsAt;
        shift.createdAt = now;
        return shift;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void delete(Instant now) {
        if (isDeleted()) {
            throw new BusinessException(ErrorCode.SHIFT_STATE_CONFLICT);
        }
        deletedAt = now;
    }

    /** 대타 승인에 따른 담당자 변경. 삭제됐거나 현재 담당자가 from이 아니거나 to가 from과 같으면 거부한다. */
    public void reassign(long fromMembershipId, long toMembershipId) {
        if (isDeleted() || assigneeMembershipId != fromMembershipId || toMembershipId == fromMembershipId) {
            throw new BusinessException(ErrorCode.SHIFT_STATE_CONFLICT);
        }
        assigneeMembershipId = toMembershipId;
    }
}
