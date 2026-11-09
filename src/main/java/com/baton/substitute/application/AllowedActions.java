package com.baton.substitute.application;

import com.baton.substitute.domain.RequestRecipient;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteStatus;
import java.time.Instant;
import java.util.List;

/**
 * 상세 화면에 보여줄 가능 행동. 표시용일 뿐이고 실제 변경 API가 다시 검사한다.
 * 이름은 API 경로의 마지막 부분과 같다(final_reject만 밑줄).
 */
public final class AllowedActions {

    public static final String CANCEL = "cancel";
    public static final String ACCEPT = "accept";
    public static final String REJECT = "reject";
    public static final String WITHDRAW = "withdraw";
    public static final String APPROVE = "approve";
    public static final String FINAL_REJECT = "final_reject";

    private AllowedActions() {
    }

    /**
     * @param myRecipient 조회자가 지정 대상이면 그 응답, 아니면 null
     */
    public static List<String> of(SubstituteRequest request, RequestRelation relation, RequestRecipient myRecipient,
                                  Instant now) {
        // 종료됐거나 시작 시각이 지난 요청(곧 만료 처리됨)에는 행동이 없다
        if (!request.isActive() || request.hasStarted(now)) {
            return List.of();
        }
        boolean waiting = request.getStatus() == SubstituteStatus.WAITING;
        return switch (relation) {
            case REQUESTER -> List.of(CANCEL);
            case CANDIDATE -> List.of(WITHDRAW);
            case TARGET -> {
                if (!waiting || myRecipient == null) {
                    yield List.of();
                }
                if (myRecipient.canDecline()) {
                    yield List.of(ACCEPT, REJECT);
                }
                yield myRecipient.canAccept() ? List.of(ACCEPT) : List.of();
            }
            case MANAGER -> waiting ? List.of() : List.of(APPROVE, FINAL_REJECT);
        };
    }
}
