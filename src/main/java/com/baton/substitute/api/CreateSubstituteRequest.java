package com.baton.substitute.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * POST /substitute-requests 본문. 요청자는 본문이 아니라 인증 정보로 정한다.
 * 대상 목록의 내용 검증(1명 이상·중복·같은 매장 크루원)은 서비스가 한다.
 */
@Schema(name = "CreateSubstituteRequest", description = "대타 요청 보내기")
public record CreateSubstituteRequest(
        @Schema(description = "대타를 부탁할 본인 담당 근무 id", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "필수 값이에요.") Long shiftId,
        @Schema(description = "요청할 동료의 소속 id. 1명 이상, 중복 없이, 같은 매장 크루원만(본인·매니저 제외). "
                + "후보 목록은 GET /stores/{store_id}/members?purpose=substitute_candidates",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "필수 값이에요.") List<Long> targetMembershipIds
) {
}
