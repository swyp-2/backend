package com.baton.store.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.member.domain.Role;
import com.baton.store.application.StoreCommandService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final StoreCommandService storeCommandService;
    private final Clock clock;

    /** ST-03: 초대 코드로 즉시 참여(승인 단계 없음). 같은 매장 재참여는 기존 소속을 200으로 돌려준다. */
    @PostMapping("/join")
    public ApiResponse<JoinResponse> join(@CurrentUser AuthUser authUser, @RequestBody JoinRequest request) {
        StoreCommandService.JoinedStore joined = storeCommandService.join(authUser.userId(), request.code());
        return ApiResponse.ok(
                new JoinResponse(joined.storeId(), joined.storeName(), joined.membershipId(), joined.role()), clock);
    }

    public record JoinRequest(String code) {
    }

    public record JoinResponse(long storeId, String storeName, long membershipId, Role role) {
    }
}
