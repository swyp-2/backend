package com.baton.substitute.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 대타 요청 조회 결과. 엔티티 대신 이 record들을 그대로 API 응답(data)으로 쓴다(JSON은 snake_case).
 * 이메일·소셜 식별자·user id는 넣지 않는다. 사람은 소속 id와 표시 이름으로만 나타낸다.
 *
 * <p>Swagger 스키마 이름은 다른 기능과 겹치지 않게 Substitute 접두사를 붙인다.
 */
public final class SubstituteRequestViews {

    private SubstituteRequestViews() {
    }

    @Schema(name = "SubstituteMember", description = "사람 표시. 탈퇴·연결 해제면 display_name이 \"탈퇴한 구성원\"")
    public record MemberRef(long membershipId, String displayName) {
    }

    @Schema(name = "SubstituteShift", description = "요청 생성 시점의 원본 근무 정보")
    public record ShiftRef(
            long shiftId,
            Instant startsAt,
            Instant endsAt,
            @Schema(description = "상세에서만 채운다. 근무가 삭제됐으면 true(\"삭제 전 정보\"로 표시). 목록에서는 null",
                    nullable = true)
            Boolean deleted) {
    }

    @Schema(name = "SubstituteRecipient", description = "지정 대상의 응답")
    public record RecipientView(
            long membershipId,
            String displayName,
            @Schema(allowableValues = {"PENDING", "ACCEPTED", "DECLINED", "UNAVAILABLE"})
            String response) {
    }

    @Schema(name = "SubstituteRequestEvent", description = "진행 기록 한 건")
    public record EventView(
            @Schema(allowableValues = {"REQUEST_CREATED", "CANDIDATE_ACCEPTED", "TARGET_DECLINED",
                    "REQUEST_CANCELLED", "CANDIDATE_WITHDRAWN", "MANAGER_APPROVED", "MANAGER_REJECTED",
                    "REQUEST_EXPIRED", "SOURCE_DELETED", "MEMBER_LEFT"})
            String kind,
            @Schema(description = "행위자. 만료 등 시스템 처리면 null", nullable = true)
            MemberRef actor,
            Instant occurredAt) {
    }

    @Schema(name = "SubstituteRequestDetail", description = "RQ-03 / AP-02 요청 상세")
    public record Detail(
            long id,
            @Schema(allowableValues = {"WAITING", "PENDING_MANAGER", "APPROVED", "REJECTED", "ALL_DECLINED",
                    "CANCELLED", "WITHDRAWN", "EXPIRED", "CANCELLED_SOURCE_CHANGED"})
            String status,
            @Schema(description = "사용자 행동이 아닌 종료의 사유. 없으면 null", nullable = true,
                    allowableValues = {"REQUESTER_LEFT", "CANDIDATE_LEFT", "NO_ACTIVE_TARGET", "SHIFT_DELETED"})
            String terminalReason,
            long version,
            Instant createdAt,
            @Schema(description = "종료 시각. 진행 중이면 null", nullable = true)
            Instant closedAt,
            ShiftRef shift,
            MemberRef requester,
            @Schema(description = "첫 수락 후보. 수락 전이면 null", nullable = true)
            MemberRef candidate,
            @Schema(allowableValues = {"REQUESTER", "CANDIDATE", "TARGET", "MANAGER"})
            String myRelation,
            @Schema(description = "요청자·매니저에게는 전체, 지정 대상·후보에게는 본인 것만")
            List<RecipientView> recipients,
            @Schema(description = "표시용 가능 행동: cancel / accept / reject / withdraw / approve / final_reject. "
                    + "종료됐거나 근무 시작 시각이 지나면 빈 배열")
            List<String> allowedActions,
            List<EventView> events) {
    }

    @Schema(name = "SubstituteRequestSummary", description = "RQ-02 목록 카드")
    public record Summary(
            long id,
            String status,
            @Schema(nullable = true)
            String terminalReason,
            ShiftRef shift,
            MemberRef requester,
            @Schema(nullable = true)
            MemberRef candidate,
            @Schema(description = "받은 요청 목록에서만 본인 응답. 보낸 요청 목록에서는 null", nullable = true)
            String myResponse,
            Instant createdAt) {
    }

    @Schema(name = "SubstituteRequestPage", description = "커서 목록")
    public record Page(
            List<Summary> items,
            @Schema(description = "다음 페이지 커서(불투명 문자열). 없으면 null", nullable = true)
            String nextCursor,
            boolean hasMore) {
    }
}
