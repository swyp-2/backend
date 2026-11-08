package com.baton.member.api;

import com.baton.member.application.AgreementCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /me/consents. 동의 시각은 받지 않는다(서버 Clock 기록).
 */
public record ConsentAgreeRequest(@NotNull(message = "필수 값이에요.") List<@NotNull @Valid Agreement> agreements) {

    public record Agreement(
            @NotNull(message = "필수 값이에요.") Long documentId,
            @NotNull(message = "필수 값이에요.") Boolean agreed
    ) {
    }

    List<AgreementCommand> toCommands() {
        return agreements.stream()
                .map(a -> new AgreementCommand(a.documentId(), a.agreed()))
                .toList();
    }
}
