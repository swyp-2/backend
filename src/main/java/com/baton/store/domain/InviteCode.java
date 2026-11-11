package com.baton.store.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 매장 공통 초대 코드(영문 대문자·숫자 8자리). 매장별 유효 코드는 한 개이며 재발급 UI는 없다.
 */
@Getter
@Entity
@Table(name = "invite_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invite_codes_seq")
    @SequenceGenerator(name = "invite_codes_seq", sequenceName = "invite_codes_seq", allocationSize = 50)
    private Long id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code", nullable = false, length = 8, updatable = false)
    private String code;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    public static InviteCode issue(Long storeId, String code, Instant now) {
        InviteCode inviteCode = new InviteCode();
        inviteCode.storeId = storeId;
        inviteCode.code = code;
        inviteCode.createdAt = now;
        return inviteCode;
    }
}
