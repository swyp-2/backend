package com.baton.config;

import com.baton.auth.security.CurrentUser;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    static {
        // @CurrentUser 파라미터는 요청 입력이 아니므로 문서에서 숨긴다
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    /**
     * 문서 스키마의 필드명을 실제 응답(spring.jackson.property-naming-strategy=SNAKE_CASE)과 맞춘다.
     * springdoc의 스키마 생성기(swagger-core)는 Jackson 2 ObjectMapper를 따로 써서 Spring Boot의 Jackson 3 설정이
     * 전달되지 않는다. 이 빈이 없으면 문서에는 storeId처럼 camelCase로 나온다. (2026-11-09 B 추가)
     */
    @Bean
    public ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    public OpenAPI batonOpenApi() {
        return new OpenAPI()
                .info(new Info().title("BATON API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
