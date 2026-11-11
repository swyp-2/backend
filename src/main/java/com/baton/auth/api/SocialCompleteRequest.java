package com.baton.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialCompleteRequest(
        @Schema(description = "Google·Apple SDK가 준 ID 토큰(JWT) 원문. 최대 8192자",
                example = "google_id_token_example", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 8192)
        @NotBlank @Size(max = 8192) String idToken
) {
}
