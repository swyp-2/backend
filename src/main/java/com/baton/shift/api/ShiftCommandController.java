package com.baton.shift.api;

import com.baton.auth.security.AuthUser;
import com.baton.auth.security.CurrentUser;
import com.baton.common.ApiResponse;
import com.baton.shift.application.ShiftCommandService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShiftCommandController {

    private final ShiftCommandService shiftCommandService;
    private final Clock clock;

    /**
     * SCH-07: 매니저의 근무 한 건 삭제(과거 근무 포함). 진행 중 대타 요청도 함께 종료한다.
     * <ul>
     *   <li>expected_version 누락·형식 오류: 422 VALIDATION_FAILED</li>
     *   <li>매니저 아님·다른 매장: 403 / 없는 근무: 404</li>
     *   <li>이미 삭제됨(current_state=DELETED), 버전·담당자가 바뀜(current_state=CHANGED): 409 SHIFT_STATE_CONFLICT.
     *       프론트는 최신 근무를 다시 조회한다</li>
     * </ul>
     */
    @DeleteMapping("/shifts/{shiftId}")
    public ApiResponse<ShiftDeleteResponse> delete(@CurrentUser AuthUser authUser, @PathVariable long shiftId,
                                                   @RequestParam("expected_version") long expectedVersion) {
        return ApiResponse.ok(ShiftDeleteResponse.from(
                shiftCommandService.delete(authUser.userId(), shiftId, expectedVersion)), clock);
    }
}
