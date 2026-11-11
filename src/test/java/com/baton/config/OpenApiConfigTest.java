package com.baton.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void local_커스터마이저는_X_Test_User_Id_헤더_입력칸을_Bearer의_대안으로_추가한다() {
        OpenAPI openApi = config.batonOpenApi();

        config.testUserHeaderOpenApiCustomizer().customise(openApi);

        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get(OpenApiConfig.TEST_USER_AUTH);
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(scheme.getName()).isEqualTo("X-Test-User-Id");
        // 보안 요구사항 항목끼리는 OR: Bearer와 테스트 헤더 중 하나만 있으면 된다
        assertThat(openApi.getSecurity()).extracting(SecurityRequirement::keySet)
                .containsExactly(java.util.Set.of("bearerAuth"), java.util.Set.of(OpenApiConfig.TEST_USER_AUTH));
    }

    @Test
    void 테스트_헤더_커스터마이저는_local_프로필에만_등록된다() throws Exception {
        Profile profile = OpenApiConfig.class.getMethod("testUserHeaderOpenApiCustomizer").getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }
}
