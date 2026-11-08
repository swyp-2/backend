package com.baton.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * baton.auth.* 설정. prod의 jwt-secret·client-ids는 환경변수로만 주입한다.
 */
@ConfigurationProperties("baton.auth")
public record AuthProperties(
        String jwtSecret,
        /** 액세스 토큰(=로그인 세션) 수명. 사용해도 연장하지 않는다. */
        @DefaultValue("14d") Duration accessTokenTtl,
        Provider google,
        Provider apple
) {

    /** HS256 키는 최소 256비트. */
    static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("baton.auth.jwt-secret은 UTF-8 기준 32바이트 이상이어야 합니다.");
        }
        google = google != null ? google : new Provider(List.of(), null);
        apple = apple != null ? apple : new Provider(List.of(), null);
    }

    /** 소셜 제공자별 ID 토큰 검증 설정. client-ids는 aud로 허용할 앱(iOS/Android/Web) 식별자 목록. */
    public record Provider(List<String> clientIds, String jwkSetUri) {

        public Provider {
            clientIds = clientIds != null ? List.copyOf(clientIds) : List.of();
        }
    }
}
