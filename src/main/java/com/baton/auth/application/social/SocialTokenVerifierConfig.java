package com.baton.auth.application.social;

import com.baton.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class SocialTokenVerifierConfig {

    private static final Duration JWKS_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public SocialTokenVerifier socialTokenVerifier(AuthProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(JWKS_TIMEOUT);
        requestFactory.setReadTimeout(JWKS_TIMEOUT);
        return new JwksSocialTokenVerifier(properties, new RestTemplate(requestFactory), clock);
    }
}
