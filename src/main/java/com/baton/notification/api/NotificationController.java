package com.baton.notification.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.ErrorResponse;
import com.baton.notification.application.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "하단 바 \"요청\" 탭 점·요청 카드 점 (알림 목록 화면 없음)")
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final Clock clock;

    @Schema(name = "NotificationIndicator")
    public record IndicatorResponse(
            @Schema(description = "최근 7일 보관 중 미읽음 알림이 있으면 true. 하단 바 \"요청\" 탭에 점을 표시한다")
            boolean hasUnread) {
    }

    @Operation(summary = "요청 탭 점 여부", description = """
            하단 바 "요청" 탭에 점을 표시할지. 생성 후 정확히 7일이 지난 알림은 세지 않는다.
            요청 상태(승인 대기 등)가 아니라 알림 읽음 기준이다.

            - 403 FORBIDDEN: 활성 매장 소속이 없음
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "점 여부"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me/notification-indicator")
    public ApiResponse<IndicatorResponse> indicator(@CurrentUser AuthUser authUser) {
        return ApiResponse.ok(new IndicatorResponse(notificationService.hasUnread(authUser.userId())), clock);
    }

    @Operation(summary = "요청 탭 진입 (탭 점 지우기)", description = """
            요청 탭에 들어올 때 한 번 호출한다. 호출 시점까지 도착한 본인 알림을 모두 읽음 처리해 탭 점을 지운다.
            이후 도착한 알림은 미읽음으로 남아 점이 다시 표시된다. 카드 점은 지우지 않는다.
            재시도해도 안전하므로 Idempotency-Key는 받지 않는다.

            - 403 FORBIDDEN: 활성 매장 소속이 없음
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/me/notifications/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(@CurrentUser AuthUser authUser) {
        notificationService.open(authUser.userId());
    }

    @Operation(summary = "요청 카드 확인 (카드 점 지우기)", description = """
            요청 카드를 눌러 상세를 열 때 호출한다. 그 요청에 대한 본인 알림을 확인 처리해 카드 점을 지운다
            (아직 읽지 않았으면 탭 점 기준의 읽음도 함께 처리). 관계없는 요청이면 아무것도 바뀌지 않는다.
            요청 접근 권한은 상세 조회(GET /substitute-requests/{id})가 따로 검사한다.

            - 403 FORBIDDEN: 활성 매장 소속이 없음
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/me/substitute-requests/{requestId}/check")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void check(@CurrentUser AuthUser authUser, @PathVariable long requestId) {
        notificationService.check(authUser.userId(), requestId);
    }
}
