package com.baton.shift.api;

import com.baton.shift.application.ShiftBatchInput;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** POST /shift-batches/preview, POST /shift-batches 요청 본문. 최종 요청은 미리보기와 같은 입력 + preview_token. */
public final class ShiftBatchRequests {

    private ShiftBatchRequests() {
    }

    @Schema(name = "ShiftBatchRepeat", description = "반복 설정. 없거나 enabled=false면 starts_at~ends_at 한 건만 만든다")
    public record RepeatRequest(
            @Schema(description = "반복 사용 여부. null이면 false", example = "true")
            Boolean enabled,
            @Schema(description = "반복 시작일(Asia/Seoul). 첫 근무 starts_at의 날짜와 같아야 한다", example = "2026-11-11")
            LocalDate startDate,
            @Schema(description = "반복 종료일(포함). 시작일에 달력상 1개월을 더한 날짜 이하", example = "2026-12-11")
            LocalDate endDate,
            @ArraySchema(arraySchema = @Schema(description = "반복 요일. 1(월)~7(일), 1개 이상", example = "[1, 3, 5]"),
                    schema = @Schema(minimum = "1", maximum = "7"))
            List<Integer> weekdays
    ) {

        ShiftBatchInput.Repeat toRepeat() {
            return new ShiftBatchInput.Repeat(Boolean.TRUE.equals(enabled), startDate, endDate, weekdays);
        }
    }

    @Schema(name = "ShiftBatchPreviewRequest")
    public record PreviewRequest(
            @Schema(description = "대상 매장 id. 이 매장의 매니저만 등록할 수 있다", example = "1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Long storeId,
            @ArraySchema(arraySchema = @Schema(description = "근무자 소속 id(이 매장의 현재 구성원, 매니저 포함 가능). 1명 이상·중복 불가",
                    example = "[10, 11]", requiredMode = Schema.RequiredMode.REQUIRED))
            List<Long> assigneeMembershipIds,
            @Schema(description = "(첫) 근무 시작 시각(UTC ISO 8601, 분 단위)", example = "2026-11-11T00:00:00Z",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant startsAt,
            @Schema(description = "(첫) 근무 종료 시각(UTC ISO 8601, 분 단위). 시작보다 늦고 24시간 미만. 자정을 넘기면 다음 날 시각",
                    example = "2026-11-11T04:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant endsAt,
            RepeatRequest repeat
    ) {

        ShiftBatchInput toInput() {
            return ShiftBatchRequests.toInput(assigneeMembershipIds, startsAt, endsAt, repeat);
        }
    }

    /** 멱등성 지문의 기준이기도 하다(record라 필드 순서 고정). */
    @Schema(name = "ShiftBatchCreateRequest", description = "미리보기와 같은 입력 + preview_token")
    public record CreateRequest(
            @Schema(description = "대상 매장 id. 이 매장의 매니저만 등록할 수 있다", example = "1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Long storeId,
            @ArraySchema(arraySchema = @Schema(description = "근무자 소속 id(이 매장의 현재 구성원, 매니저 포함 가능). 1명 이상·중복 불가",
                    example = "[10, 11]", requiredMode = Schema.RequiredMode.REQUIRED))
            List<Long> assigneeMembershipIds,
            @Schema(description = "(첫) 근무 시작 시각(UTC ISO 8601, 분 단위)", example = "2026-11-11T00:00:00Z",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant startsAt,
            @Schema(description = "(첫) 근무 종료 시각(UTC ISO 8601, 분 단위). 시작보다 늦고 24시간 미만. 자정을 넘기면 다음 날 시각",
                    example = "2026-11-11T04:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            Instant endsAt,
            RepeatRequest repeat,
            @Schema(description = "미리보기 응답의 preview_token(불투명 문자열, 30분 유효)", example = "opaque_preview",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String previewToken
    ) {

        ShiftBatchInput toInput() {
            return ShiftBatchRequests.toInput(assigneeMembershipIds, startsAt, endsAt, repeat);
        }
    }

    private static ShiftBatchInput toInput(List<Long> assignees, Instant startsAt, Instant endsAt,
                                           RepeatRequest repeat) {
        return new ShiftBatchInput(assignees, startsAt, endsAt, repeat == null ? null : repeat.toRepeat());
    }
}
