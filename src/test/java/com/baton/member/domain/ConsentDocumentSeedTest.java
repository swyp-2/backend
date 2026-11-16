package com.baton.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.baton.support.RepositoryTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@RepositoryTest
class ConsentDocumentSeedTest {

    private static final String V1_MIGRATION = "db/migration/V202611161200__a_consent_documents_v1.sql";

    @Autowired ConsentDocumentRepository consentDocumentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 필수_약관은_운영_URL이고_마케팅은_local_전용_시드다() {
        assertThat(consentDocumentRepository.findAll())
                .extracting(ConsentDocument::getKind, ConsentDocument::getVersion, ConsentDocument::isRequired,
                        ConsentDocument::getTitle, ConsentDocument::getBodyReference)
                .containsExactlyInAnyOrder(
                        tuple("TERMS_OF_SERVICE", 1, true, "서비스 이용약관",
                                "https://swyp-2.github.io/frontend/terms/v1/"),
                        tuple("PRIVACY_POLICY", 1, true, "개인정보 처리방침",
                                "https://swyp-2.github.io/frontend/privacy/v1/"),
                        tuple("MARKETING", 1, false, "(임시) 마케팅 정보 수신 동의",
                                "https://example.com/baton/terms/marketing/v1"));
    }

    @Test
    void 같은_kind_version_행이_이미_있어도_v1_마이그레이션은_실패하지_않고_덮어쓴다() throws Exception {
        // 운영 DB에 손으로 넣어 둔 행을 흉내 낸다.
        jdbcTemplate.update("""
                update consent_documents
                   set title = '손으로 넣은 약관', body_reference = 'https://manual.example/terms', required = false
                 where kind = 'TERMS_OF_SERVICE' and version = 1
                """);
        long before = count();

        String sql = new ClassPathResource(V1_MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);

        assertThat(count()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForMap("""
                select title, body_reference, required from consent_documents
                 where kind = 'TERMS_OF_SERVICE' and version = 1
                """))
                .containsEntry("title", "서비스 이용약관")
                .containsEntry("body_reference", "https://swyp-2.github.io/frontend/terms/v1/")
                .containsEntry("required", true);
    }

    private long count() {
        Long count = jdbcTemplate.queryForObject("select count(*) from consent_documents", Long.class);
        return count == null ? 0 : count;
    }
}
