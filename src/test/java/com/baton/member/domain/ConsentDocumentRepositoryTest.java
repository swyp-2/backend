package com.baton.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.baton.support.RepositoryTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 트랜잭션 롤백되는 슬라이스라 추가 문서가 다른 테스트에 남지 않는다. */
@RepositoryTest
class ConsentDocumentRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    @Autowired ConsentDocumentRepository consentDocumentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void kind별로_시행된_최신_버전만_조회한다() {
        insert("TERMS_OF_SERVICE", 2, true, NOW.minusSeconds(60));   // 시행됨 → v1 대신 노출
        insert("PRIVACY_POLICY", 2, true, NOW.plusSeconds(60));      // 아직 시행 전 → v1 유지
        insert("FUTURE_ONLY", 1, false, NOW.plusSeconds(3600));     // 시행 전 문서만 있는 종류 → 제외

        assertThat(consentDocumentRepository.findCurrent(NOW))
                .extracting(ConsentDocument::getKind, ConsentDocument::getVersion, ConsentDocument::isRequired)
                .containsExactlyInAnyOrder(
                        tuple("TERMS_OF_SERVICE", 2, true),
                        tuple("PRIVACY_POLICY", 1, true),
                        tuple("MARKETING", 1, false));
    }

    @Test
    void 시행_시각과_같으면_포함한다() {
        insert("EXACT_NOW", 1, false, NOW);

        assertThat(consentDocumentRepository.findCurrent(NOW))
                .extracting(ConsentDocument::getKind)
                .contains("EXACT_NOW");
    }

    private void insert(String kind, int version, boolean required, Instant effectiveAt) {
        jdbcTemplate.update("""
                insert into consent_documents (id, kind, version, required, title, body_reference, effective_at)
                values (nextval('consent_documents_seq'), ?, ?, ?, '(테스트)', 'TBD', ?)
                """, kind, version, required, Timestamp.from(effectiveAt));
    }
}
