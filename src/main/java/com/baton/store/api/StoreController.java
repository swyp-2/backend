package com.baton.store.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.store.application.StoreQueryService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreQueryService storeQueryService;
    private final Clock clock;

    /** PR-02: 현재 소속 매장 기본 정보. 다른 매장·소속 없음은 403. */
    @GetMapping("/{storeId}")
    public ApiResponse<StoreResponse> get(@CurrentUser AuthUser authUser, @PathVariable long storeId) {
        StoreQueryService.StoreView view = storeQueryService.getStore(authUser.userId(), storeId);
        return ApiResponse.ok(new StoreResponse(view.storeId(), view.name()), clock);
    }

    public record StoreResponse(long storeId, String name) {
    }
}
