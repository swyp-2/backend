package com.baton.store.domain;

import com.baton.member.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 매장 소속. 현재 권한의 유일한 근거다. 탈퇴 시 user_id 연결을 끊고 행은 남긴다.
 * 상태 변경 전 호출 측은 이 행을 잠근다(잠금 순서: membership → shift → substitute_request).
 */
@Getter
@Entity
@Table(name = "memberships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "memberships_seq")
    @SequenceGenerator(name = "memberships_seq", sequenceName = "memberships_seq", allocationSize = 50)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private MembershipState state;

    @Column(name = "personal_color", nullable = false, length = 7)
    private String personalColor;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static Membership join(Long storeId, Long userId, Role role, String personalColor, Instant now) {
        Membership membership = new Membership();
        membership.storeId = storeId;
        membership.userId = userId;
        membership.role = role;
        membership.state = MembershipState.ACTIVE;
        membership.personalColor = personalColor;
        membership.joinedAt = now;
        return membership;
    }

    public boolean isActive() {
        return state == MembershipState.ACTIVE;
    }

    /** 매장 나가기. 계정은 유지하고 소속만 해제한다. */
    public void leave(Instant now) {
        requireActive();
        state = MembershipState.LEFT;
        leftAt = now;
    }

    /**
     * 계정 탈퇴. 활성·과거 소속 모두 WITHDRAWN으로 바꾸고 개인 연결(user_id)을 끊는다.
     * 활성 소속이었으면 left_at = now, 이미 나간 소속은 나간 시각을 유지한다.
     */
    public void withdraw(Instant now) {
        if (isActive()) {
            leftAt = now;
        }
        state = MembershipState.WITHDRAWN;
        userId = null;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("활성 소속이 아님: membershipId=" + id + ", state=" + state);
        }
    }
}
