package com.baton.store.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

    @Test
    void 영문_대문자_숫자_8자리() {
        InviteCodeGenerator generator = new InviteCodeGenerator();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String code = generator.generate();
            assertThat(code).matches("^[A-Z0-9]{8}$");
            codes.add(code);
        }
        assertThat(codes).hasSizeGreaterThan(990);
    }
}
