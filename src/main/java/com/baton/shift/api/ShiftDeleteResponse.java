package com.baton.shift.api;

import com.baton.shift.application.ShiftCommandService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DELETE /shifts/{shift_id} 응답.
 *
 * @param closedRequestId 함께 종료한 진행 중 대타 요청 id. 없으면 null
 */
@Schema(name = "ShiftDeleteResult")
public record ShiftDeleteResponse(
        @Schema(description = "삭제한 근무 id", example = "100")
        long shiftId,
        @Schema(description = "삭제 여부(성공 응답에서는 항상 true)", example = "true")
        boolean deleted,
        @Schema(description = "함께 종료한 진행 중 대타 요청 id. 없으면 null", example = "200")
        Long closedRequestId
) {

    static ShiftDeleteResponse from(ShiftCommandService.Deleted deleted) {
        return new ShiftDeleteResponse(deleted.shiftId(), true, deleted.closedRequestId());
    }
}
