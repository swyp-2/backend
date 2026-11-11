package com.baton.account.api;

import com.baton.account.application.AccountService;
import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PR-04 / PRO-03: 매장 나가기·계정 탈퇴. */
@Tag(name = "계정", description = "매장 나가기·계정 탈퇴")
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "매장 나가기", description = """
            확인 후 매장 소속을 해제한다. 계정·로그인은 유지되고 가입 단계는 매장 연결(ST-01/ST-03)로 돌아간다.
            진행 중인 대타 요청은 함께 정리된다(요청자면 취소, 승인 대기 후보면 철회, 지정 대상이면 응답 불가).
            나간 뒤 이전 매장 API는 모두 403. 다시 참여하려면 유효한 초대 코드로 새로 참여한다.

            - 403 FORBIDDEN: 이 매장의 활성 소속이 아님(다른 매장, 이미 나감)
            - 409 FUTURE_SHIFT_REMAINS: 끝나지 않은 본인 근무(예정·진행 중)가 있음. 아무것도 바뀌지 않는다
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "나감"),
            @ApiResponse(responseCode = "403", description = "소속 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "남은 근무 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/stores/{storeId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@CurrentUser AuthUser authUser, @PathVariable long storeId) {
        accountService.leave(authUser.userId(), storeId);
    }

    @Operation(summary = "계정 탈퇴", description = """
            확인 후 계정을 종료한다. 모든 세션이 즉시 끝나 기존 토큰은 401이 된다.
            이름·소셜 식별자·동의 기록·소속 연결을 파기하고, 다른 구성원의 근무표·기록에는 "탈퇴한 구성원"으로 표시된다.
            같은 소셜 계정으로 다시 로그인하면 새 회원으로 가입한다.

            - 409 FUTURE_SHIFT_REMAINS: 끝나지 않은 본인 근무(예정·진행 중)가 있음. 아무것도 바뀌지 않는다
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "탈퇴함"),
            @ApiResponse(responseCode = "409", description = "남은 근무 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@CurrentUser AuthUser authUser) {
        accountService.withdraw(authUser.userId());
    }
}
