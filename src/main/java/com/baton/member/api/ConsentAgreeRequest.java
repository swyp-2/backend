package com.baton.member.api;

import com.baton.member.application.AgreementCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /me/consents. 동의 시각은 받지 않는다(서버 Clock 기록).
 */
public record ConsentAgreeRequest(
        @Schema(description = "문서별 동의 여부. 현재 유효한 필수 문서는 모두 agreed=true여야 한다. 빠진 선택 문서는 미동의로 기록",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "필수 값이에요.") List<@NotNull @Valid Agreement> agreements) {

    public record Agreement(
            @Schema(description = "GET /consent-documents의 document_id", example = "1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "필수 값이에요.") Long documentId,
            @Schema(description = "동의 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "필수 값이에요.") Boolean agreed
    ) {
    }

    List<AgreementCommand> toCommands() {
        return agreements.stream()
                .map(a -> new AgreementCommand(a.documentId(), a.agreed()))
                .toList();
    }
}
