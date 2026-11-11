package com.baton.store.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.ErrorResponse;
import com.baton.common.FieldError;
import com.baton.common.idempotency.IdempotencyCommand;
import com.baton.common.idempotency.IdempotencyScope;
import com.baton.common.idempotency.IdempotencyService;
import com.baton.member.domain.Role;
import com.baton.store.application.StoreCommandService;
import com.baton.store.application.StoreQueryService;
import com.baton.store.application.StoreQueryService.MemberPurpose;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "매장", description = "매장 만들기·매장 정보·초대 코드·코드로 참여·구성원")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreQueryService storeQueryService;
    private final StoreCommandService storeCommandService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    /**
     * ST-01: 매장 + 생성자 매니저 소속 + 초대 코드 생성. Idempotency-Key 필수(같은 키 재요청은 처음 결과 그대로).
     */
    @Operation(summary = "매장 만들기", description = """
            STR-01 / ST-01

            매장, 생성자의 MANAGER 소속, 매장 공통 초대 코드를 한 번에 만든다. 가입 단계 STORE_REQUIRED → COMPLETED.
            같은 Idempotency-Key·같은 본문으로 다시 보내면 처음 결과를 그대로 돌려준다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 매장 연결 단계(STORE_REQUIRED)가 아니거나 가입 역할이 MANAGER가 아님
            - 409 ALREADY_IN_STORE: 이미 활성 매장 소속이 있음(다른 키로 연속 제출한 두 번째 요청 포함)
            - 422 VALIDATION_FAILED: Idempotency-Key 누락·형식 오류(field: Idempotency-Key),
              매장명 누락·앞뒤 공백 제거 후 1~15자 아님·줄바꿈·제어문자(field: name), 본문을 읽을 수 없음(field: body)
            - 422 IDEMPOTENCY_KEY_REUSED: 같은 키를 다른 본문으로 재사용
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "만든 매장"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "단계·역할 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 매장 소속",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "입력 오류·Idempotency-Key 오류·재사용",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ApiResponse<CreateStoreResponse> create(
            @CurrentUser AuthUser authUser,
            @Parameter(in = ParameterIn.HEADER, required = true, example = "store-create-3f9a1c",
                    description = "중복 제출 방지 키. 영문·숫자·-·_ 1~100자. 재시도할 때는 같은 키를 다시 보낸다")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody CreateStoreRequest request) {
        CreateStoreResponse result = idempotencyService.execute(
                new IdempotencyCommand(authUser.userId(), IdempotencyScope.STORE_CREATE, idempotencyKey,
                        new CreateStorePayload(request.name())),
                CreateStoreResponse.class,
                () -> CreateStoreResponse.from(storeCommandService.create(authUser.userId(), request.name())));
        return ApiResponse.ok(result, clock);
    }

    /** PR-02: 현재 소속 매장 기본 정보. 다른 매장·소속 없음은 403. */
    @Operation(summary = "매장 정보 조회", description = """
            PRO-01 / PR-02

            현재 소속 매장의 이름·실제 구성원 수·내 역할을 돌려준다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 활성 소속이 아님(없는 매장도 403, 존재 여부 비노출)
            - 422 VALIDATION_FAILED: storeId 형식 오류(field: store_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "매장 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "경로 형식 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{storeId}")
    public ApiResponse<StoreResponse> get(@CurrentUser AuthUser authUser,
                                          @Parameter(description = "매장 id", example = "1")
                                          @PathVariable long storeId) {
        StoreQueryService.StoreView view = storeQueryService.getStore(authUser.userId(), storeId);
        return ApiResponse.ok(new StoreResponse(view.storeId(), view.name(), view.memberCount(), view.myRole()),
                clock);
    }

    /** ST-02/PR-02: 매장 공통 초대 코드. 매니저만. 재발급 API는 없다. */
    @Operation(summary = "초대 코드 조회", description = """
            STR-02 / ST-02, PR-02

            매장 공통 초대 코드(영문 대문자·숫자 8자리)를 돌려준다. 이 매장의 매니저만 볼 수 있다. 재발급 API는 없다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 활성 소속이 아니거나 매니저가 아님
            - 404 NOT_FOUND: 유효한 초대 코드가 없음
            - 422 VALIDATION_FAILED: storeId 형식 오류(field: store_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초대 코드"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "매니저 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "코드 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "경로 형식 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{storeId}/invite-code")
    public ApiResponse<InviteCodeResponse> inviteCode(@CurrentUser AuthUser authUser,
                                                      @Parameter(description = "매장 id", example = "1")
                                                      @PathVariable long storeId) {
        return ApiResponse.ok(new InviteCodeResponse(storeQueryService.getInviteCode(authUser.userId(), storeId)),
                clock);
    }

    /** PR-02/ED-01/RQ-01: 매장 구성원. purpose=all(기본) | substitute_candidates. */
    @Operation(summary = "매장 구성원 조회", description = """
            PRO-01, SCH-03, SUB-01 / PR-02, ED-01, RQ-01

            현재 활성 구성원을 매니저 먼저, 한국어 이름순, 같으면 membership_id 순으로 돌려준다.
            purpose=substitute_candidates면 본인·매니저를 뺀 크루원만(대타 후보, RQ-01).
            근무 배정(ED-01)에는 매니저를 포함하는 all을 쓴다.

            - 401 UNAUTHENTICATED: 인증 필요
            - 403 FORBIDDEN: 이 매장의 활성 소속이 아님
            - 422 VALIDATION_FAILED: purpose가 all·substitute_candidates 외 값(field: purpose),
              storeId 형식 오류(field: store_id)
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "구성원 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소속 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "입력 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{storeId}/members")
    public ApiResponse<List<MemberResponse>> members(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "매장 id", example = "1") @PathVariable long storeId,
            @Parameter(description = "all(기본, 매니저 포함 전체) 또는 substitute_candidates(본인·매니저 제외 크루원)",
                    schema = @Schema(allowableValues = {"all", "substitute_candidates"}, defaultValue = "all"))
            @RequestParam(required = false) String purpose) {
        List<MemberResponse> members = storeQueryService.getMembers(authUser.userId(), storeId, parsePurpose(purpose))
                .stream()
                .map(m -> new MemberResponse(m.membershipId(), m.displayName(), m.role(), m.personalColor(), m.isMe()))
                .toList();
        return ApiResponse.ok(members, clock);
    }

    private static MemberPurpose parsePurpose(String purpose) {
        if (purpose == null || purpose.equals("all")) {
            return MemberPurpose.ALL;
        }
        if (purpose.equals("substitute_candidates")) {
            return MemberPurpose.SUBSTITUTE_CANDIDATES;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                List.of(new FieldError("purpose", "all 또는 substitute_candidates 중 하나로 보내 주세요.")));
    }

    public record CreateStoreRequest(
            @Schema(description = "매장명. 앞뒤 공백 제거 후 1~15자, 줄바꿈·제어문자 불가", example = "바톤 카페",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name
    ) {
    }

    /** 멱등성 지문 기준(요청 본문 그대로). */
    record CreateStorePayload(String name) {
    }

    public record CreateStoreResponse(
            @Schema(description = "매장 id", example = "1")
            long storeId,
            @Schema(description = "저장된 매장명(앞뒤 공백 제거)", example = "바톤 카페")
            String name,
            @Schema(description = "생성자의 MANAGER 소속 id", example = "10")
            long membershipId,
            @Schema(description = "매장 공통 초대 코드(영문 대문자·숫자 8자리)", example = "BATON726")
            String inviteCode
    ) {

        static CreateStoreResponse from(StoreCommandService.CreatedStore created) {
            return new CreateStoreResponse(created.storeId(), created.name(), created.membershipId(),
                    created.inviteCode());
        }
    }

    public record StoreResponse(
            @Schema(description = "매장 id", example = "1")
            long storeId,
            @Schema(description = "매장명", example = "바톤 카페")
            String name,
            @Schema(description = "현재 활성 구성원 수(매니저 포함)", example = "5")
            long memberCount,
            @Schema(description = "이 매장에서 내 역할", example = "MANAGER")
            Role myRole
    ) {
    }

    public record InviteCodeResponse(
            @Schema(description = "매장 공통 초대 코드(영문 대문자·숫자 8자리)", example = "BATON726")
            String code
    ) {
    }

    public record MemberResponse(
            @Schema(description = "소속 id", example = "10")
            long membershipId,
            @Schema(description = "구성원 이름", example = "김도연")
            String displayName,
            @Schema(description = "매장 안 역할", example = "CREW")
            Role role,
            @Schema(description = "개인 색상(#RRGGBB 대문자)", example = "#E5484D")
            String personalColor,
            @Schema(description = "본인 여부", example = "true")
            boolean isMe
    ) {
    }
}
