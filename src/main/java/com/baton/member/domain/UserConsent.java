package com.baton.member.domain;

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

@Getter
@Entity
@Table(name = "user_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_consents_seq")
    @SequenceGenerator(name = "user_consents_seq", sequenceName = "user_consents_seq", allocationSize = 50)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "agreed", nullable = false)
    private boolean agreed;

    /** 동의 여부(agreed true/false)를 결정한 시각. */
    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    public static UserConsent decide(Long userId, Long documentId, boolean agreed, Instant now) {
        UserConsent consent = new UserConsent();
        consent.userId = userId;
        consent.documentId = documentId;
        consent.agreed = agreed;
        consent.agreedAt = now;
        return consent;
    }
}
