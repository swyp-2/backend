package com.baton.config;

import com.baton.auth.security.CurrentUser;
import com.baton.auth.security.TestUserAuthenticationFilter;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";
    static final String TEST_USER_AUTH = "testUserId";

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

    /**
     * local 프로필 전용: Swagger UI의 Authorize 창에 X-Test-User-Id 입력칸을 띄운다.
     * Bearer와 둘 중 하나만 넣으면 된다(보안 요구사항을 OR로 추가). 인증 필터 자체는 TestUserAuthConfig가 등록한다.
     */
    @Bean
    @Profile("local")
    public OpenApiCustomizer testUserHeaderOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes(TEST_USER_AUTH, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name(TestUserAuthenticationFilter.HEADER)
                    .description("local 프로필 전용 테스트 인증. 활성 회원의 user_id를 넣는다(예: 1)"));
            openApi.addSecurityItem(new SecurityRequirement().addList(TEST_USER_AUTH));
        };
    }
}
