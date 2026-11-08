package com.baton.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    @Test
    void 토큰은_32바이트_Base64URL이고_매번_다르다() {
        String first = RefreshTokens.generate();
        String second = RefreshTokens.generate();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 해시는_64자_소문자_hex이고_결정적이다() {
        String token = RefreshTokens.generate();

        assertThat(RefreshTokens.hash(token)).matches("[0-9a-f]{64}").isEqualTo(RefreshTokens.hash(token));
        assertThat(RefreshTokens.hash(token)).isNotEqualTo(token);
    }
}
