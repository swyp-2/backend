package com.baton.store.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.common.FieldError;
import com.baton.common.idempotency.IdempotencyCommand;
import com.baton.common.idempotency.IdempotencyScope;
import com.baton.common.idempotency.IdempotencyService;
import com.baton.member.domain.Role;
import com.baton.store.application.StoreCommandService;
import com.baton.store.application.StoreQueryService;
import com.baton.store.application.StoreQueryService.MemberPurpose;
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
    @PostMapping
    public ApiResponse<CreateStoreResponse> create(
            @CurrentUser AuthUser authUser,
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
    @GetMapping("/{storeId}")
    public ApiResponse<StoreResponse> get(@CurrentUser AuthUser authUser, @PathVariable long storeId) {
        StoreQueryService.StoreView view = storeQueryService.getStore(authUser.userId(), storeId);
        return ApiResponse.ok(new StoreResponse(view.storeId(), view.name(), view.memberCount(), view.myRole()),
                clock);
    }

    /** ST-02/PR-02: 매장 공통 초대 코드. 매니저만. 재발급 API는 없다. */
    @GetMapping("/{storeId}/invite-code")
    public ApiResponse<InviteCodeResponse> inviteCode(@CurrentUser AuthUser authUser, @PathVariable long storeId) {
        return ApiResponse.ok(new InviteCodeResponse(storeQueryService.getInviteCode(authUser.userId(), storeId)),
                clock);
    }

    /** PR-02/ED-01/RQ-01: 매장 구성원. purpose=all(기본) | substitute_candidates. */
    @GetMapping("/{storeId}/members")
    public ApiResponse<List<MemberResponse>> members(@CurrentUser AuthUser authUser, @PathVariable long storeId,
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

    public record CreateStoreRequest(String name) {
    }

    /** 멱등성 지문 기준(요청 본문 그대로). */
    record CreateStorePayload(String name) {
    }

    public record CreateStoreResponse(long storeId, String name, long membershipId, String inviteCode) {

        static CreateStoreResponse from(StoreCommandService.CreatedStore created) {
            return new CreateStoreResponse(created.storeId(), created.name(), created.membershipId(),
                    created.inviteCode());
        }
    }

    public record StoreResponse(long storeId, String name, long memberCount, Role myRole) {
    }

    public record InviteCodeResponse(String code) {
    }

    public record MemberResponse(long membershipId, String displayName, Role role, String personalColor,
                                 boolean isMe) {
    }
}
