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

/**
 * 약관 문서 버전. 운영 문서 종류가 미정이라 kind는 enum이 아닌 String으로 둔다.
 * 문서는 SQL(시드·운영 마이그레이션)로만 등록하므로 생성 메서드가 없다.
 */
@Getter
@Entity
@Table(name = "consent_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "consent_documents_seq")
    @SequenceGenerator(name = "consent_documents_seq", sequenceName = "consent_documents_seq", allocationSize = 50)
    private Long id;

    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "body_reference", nullable = false, length = 500)
    private String bodyReference;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
}
