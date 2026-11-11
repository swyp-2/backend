package com.baton.shift.api;

import com.baton.shift.application.ShiftBatchService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ShiftBatchResponses {

    private ShiftBatchResponses() {
    }

    /** items는 시작 시각 → membership_id 순. duplicate=true인 건은 최종 등록에서 만들지 않는다. */
    @Schema(name = "ShiftBatchPreview")
    public record PreviewResponse(
            @Schema(description = "최종 등록에 그대로 보낼 불투명 문자열(30분 유효). 형식에 의존하지 말 것",
                    example = "opaque_preview")
            String previewToken,
            @Schema(description = "새로 만들 근무 수", example = "26")
            int createCount,
            @Schema(description = "완전 동일 중복이라 만들지 않을 근무 수", example = "2")
            int duplicateCount,
            @Schema(description = "계획한 전체 근무 수(create_count + duplicate_count)", example = "28")
            int totalCount,
            @ArraySchema(arraySchema = @Schema(description = "계획한 근무. 시작 시각 → membership_id 순"))
            List<PreviewItemResponse> items
    ) {

        static PreviewResponse from(ShiftBatchService.Preview preview) {
            return new PreviewResponse(preview.previewToken(), preview.createCount(), preview.duplicateCount(),
                    preview.totalCount(), preview.items().stream().map(PreviewItemResponse::from).toList());
        }
    }

    @Schema(name = "ShiftBatchPreviewItem")
    public record PreviewItemResponse(
            @Schema(description = "근무자 소속 id", example = "10")
            long membershipId,
            @Schema(description = "근무자 이름", example = "김도연")
            String displayName,
            @Schema(description = "근무 날짜(starts_at의 Asia/Seoul 날짜)", example = "2026-11-11")
            LocalDate localDate,
            @Schema(description = "시작 시각(UTC)", example = "2026-11-11T00:00:00Z")
            Instant startsAt,
            @Schema(description = "종료 시각(UTC)", example = "2026-11-11T04:00:00Z")
            Instant endsAt,
            @Schema(description = "이미 같은 근무(담당자·시작·종료 동일)가 있어 만들지 않음", example = "false")
            boolean duplicate
    ) {

        static PreviewItemResponse from(ShiftBatchService.PreviewItem item) {
            return new PreviewItemResponse(item.membershipId(), item.displayName(), item.localDate(),
                    item.startsAt(), item.endsAt(), item.duplicate());
        }
    }

    /** 실제로 저장한 건만. shift_ids는 시작 시각 → membership_id 순. */
    @Schema(name = "ShiftBatchCreated")
    public record CreateResponse(
            @Schema(description = "실제로 저장한 근무 수", example = "26")
            int createdCount,
            @Schema(description = "완전 동일 중복이라 만들지 않은 근무 수", example = "2")
            int duplicateCount,
            @ArraySchema(arraySchema = @Schema(description = "저장한 근무 id. 시작 시각 → membership_id 순",
                    example = "[100, 101]"))
            List<Long> shiftIds
    ) {

        static CreateResponse from(ShiftBatchService.Created created) {
            return new CreateResponse(created.createdCount(), created.duplicateCount(), created.shiftIds());
        }
    }
}
