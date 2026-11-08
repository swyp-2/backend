package com.baton.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SocialCompleteRequest(
        @NotBlank @Size(max = 8192) String idToken
) {
}
