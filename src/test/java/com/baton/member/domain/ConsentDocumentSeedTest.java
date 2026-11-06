package com.baton.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.baton.support.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class ConsentDocumentSeedTest {

    @Autowired ConsentDocumentRepository consentDocumentRepository;

    @Test
    void test_프로필에는_임시_약관_시드가_들어간다() {
        assertThat(consentDocumentRepository.findAll())
                .extracting(ConsentDocument::getKind, ConsentDocument::getVersion, ConsentDocument::isRequired,
                        ConsentDocument::getBodyReference)
                .containsExactlyInAnyOrder(
                        tuple("TERMS_OF_SERVICE", 1, true, "TBD"),
                        tuple("PRIVACY_POLICY", 1, true, "TBD"),
                        tuple("MARKETING", 1, false, "TBD"));
    }
}
