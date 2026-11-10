package com.baton.shift.api;

import com.baton.shift.application.ShiftBatchInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** POST /shift-batches/preview, POST /shift-batches 요청 본문. 최종 요청은 미리보기와 같은 입력 + preview_token. */
public final class ShiftBatchRequests {

    private ShiftBatchRequests() {
    }

    @Schema(name = "ShiftBatchRepeat")
    public record RepeatRequest(Boolean enabled, LocalDate startDate, LocalDate endDate, List<Integer> weekdays) {

        ShiftBatchInput.Repeat toRepeat() {
            return new ShiftBatchInput.Repeat(Boolean.TRUE.equals(enabled), startDate, endDate, weekdays);
        }
    }

    @Schema(name = "ShiftBatchPreviewRequest")
    public record PreviewRequest(Long storeId, List<Long> assigneeMembershipIds, Instant startsAt, Instant endsAt,
                                 RepeatRequest repeat) {

        ShiftBatchInput toInput() {
            return ShiftBatchRequests.toInput(assigneeMembershipIds, startsAt, endsAt, repeat);
        }
    }

    /** 멱등성 지문의 기준이기도 하다(record라 필드 순서 고정). */
    @Schema(name = "ShiftBatchCreateRequest")
    public record CreateRequest(Long storeId, List<Long> assigneeMembershipIds, Instant startsAt, Instant endsAt,
                                RepeatRequest repeat, String previewToken) {

        ShiftBatchInput toInput() {
            return ShiftBatchRequests.toInput(assigneeMembershipIds, startsAt, endsAt, repeat);
        }
    }

    private static ShiftBatchInput toInput(List<Long> assignees, Instant startsAt, Instant endsAt,
                                           RepeatRequest repeat) {
        return new ShiftBatchInput(assignees, startsAt, endsAt, repeat == null ? null : repeat.toRepeat());
    }
}
