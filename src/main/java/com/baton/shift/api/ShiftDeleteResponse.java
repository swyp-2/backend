package com.baton.shift.api;

import com.baton.shift.application.ShiftCommandService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DELETE /shifts/{shift_id} 응답.
 *
 * @param closedRequestId 함께 종료한 진행 중 대타 요청 id. 없으면 null
 */
@Schema(name = "ShiftDeleteResult")
public record ShiftDeleteResponse(long shiftId, boolean deleted, Long closedRequestId) {

    static ShiftDeleteResponse from(ShiftCommandService.Deleted deleted) {
        return new ShiftDeleteResponse(deleted.shiftId(), true, deleted.closedRequestId());
    }
}
