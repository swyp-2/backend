package com.baton.shift.api;

import com.baton.shift.application.ShiftBatchService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ShiftBatchResponses {

    private ShiftBatchResponses() {
    }

    /** items는 시작 시각 → membership_id 순. duplicate=true인 건은 최종 등록에서 만들지 않는다. */
    @Schema(name = "ShiftBatchPreview")
    public record PreviewResponse(String previewToken, int createCount, int duplicateCount, int totalCount,
                                  List<PreviewItemResponse> items) {

        static PreviewResponse from(ShiftBatchService.Preview preview) {
            return new PreviewResponse(preview.previewToken(), preview.createCount(), preview.duplicateCount(),
                    preview.totalCount(), preview.items().stream().map(PreviewItemResponse::from).toList());
        }
    }

    @Schema(name = "ShiftBatchPreviewItem")
    public record PreviewItemResponse(long membershipId, String displayName, LocalDate localDate, Instant startsAt,
                                      Instant endsAt, boolean duplicate) {

        static PreviewItemResponse from(ShiftBatchService.PreviewItem item) {
            return new PreviewItemResponse(item.membershipId(), item.displayName(), item.localDate(),
                    item.startsAt(), item.endsAt(), item.duplicate());
        }
    }

    /** 실제로 저장한 건만. shift_ids는 시작 시각 → membership_id 순. */
    @Schema(name = "ShiftBatchCreated")
    public record CreateResponse(int createdCount, int duplicateCount, List<Long> shiftIds) {

        static CreateResponse from(ShiftBatchService.Created created) {
            return new CreateResponse(created.createdCount(), created.duplicateCount(), created.shiftIds());
        }
    }
}
