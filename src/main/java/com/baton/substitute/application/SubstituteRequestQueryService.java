package com.baton.substitute.application;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.integration.ActiveMembership;
import com.baton.integration.MembershipQuery;
import com.baton.integration.ShiftAssignment;
import com.baton.substitute.application.SubstituteRequestViews.Detail;
import com.baton.substitute.application.SubstituteRequestViews.EventView;
import com.baton.substitute.application.SubstituteRequestViews.MemberRef;
import com.baton.substitute.application.SubstituteRequestViews.Page;
import com.baton.substitute.application.SubstituteRequestViews.RecipientView;
import com.baton.substitute.application.SubstituteRequestViews.ShiftRef;
import com.baton.substitute.application.SubstituteRequestViews.Summary;
import com.baton.substitute.domain.RequestRecipient;
import com.baton.substitute.domain.RequestRecipientRepository;
import com.baton.substitute.domain.RequestEventRepository;
import com.baton.substitute.domain.SubstituteRequest;
import com.baton.substitute.domain.SubstituteRequestRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대타 요청 조회(SUB-04 상세, SUB-05 보낸·받은 목록). 상태를 바꾸지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubstituteRequestQueryService {

    public static final int PAGE_SIZE = 20;
    private static final String MANAGER = "MANAGER";
    private static final String CREW = "CREW";

    private final SubstituteRequestRepository requestRepository;
    private final RequestRecipientRepository recipientRepository;
    private final RequestEventRepository eventRepository;
    private final MembershipQuery membershipQuery;
    private final ShiftAssignment shiftAssignment;
    private final Clock clock;

    /**
     * 요청 상세. 같은 매장의 활성 소속이면서 요청자·지정 대상·매니저인 사람만 볼 수 있다(그 밖은 FORBIDDEN).
     */
    public Detail getDetail(long userId, long requestId) {
        SubstituteRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ActiveMembership me = membershipQuery.requireActive(userId, request.getStoreId());

        List<RequestRecipient> recipients = recipientRepository.findByRequestIdOrderByIdAsc(requestId);
        RequestRecipient myRecipient = recipients.stream()
                .filter(r -> r.getTargetMembershipId() == me.membershipId())
                .findFirst()
                .orElse(null);
        RequestRelation relation = relationOf(request, me, myRecipient);

        Names names = new Names(membershipQuery);
        boolean seesAllRecipients = relation == RequestRelation.REQUESTER || relation == RequestRelation.MANAGER;
        List<RecipientView> recipientViews = recipients.stream()
                .filter(r -> seesAllRecipients || r == myRecipient)
                .map(r -> new RecipientView(r.getTargetMembershipId(), names.of(r.getTargetMembershipId()),
                        r.getResponse().name()))
                .toList();
        List<EventView> events = eventRepository.findByRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                .map(e -> new EventView(e.getKind().name(), names.ref(e.getActorMembershipId()), e.getOccurredAt()))
                .toList();
        boolean shiftDeleted = shiftAssignment.getSnapshot(request.getShiftId()).deleted();

        return new Detail(request.getId(), request.getStatus().name(), nameOrNull(request.getTerminalReason()),
                request.getVersion(), request.getCreatedAt(), request.getClosedAt(),
                new ShiftRef(request.getShiftId(), request.getSourceStartsAt(), request.getSourceEndsAt(),
                        shiftDeleted),
                names.ref(request.getRequesterMembershipId()), names.ref(request.getCandidateMembershipId()),
                relation.name(), recipientViews, AllowedActions.of(request, relation, myRecipient, clock.instant()),
                events);
    }

    /**
     * 본인(크루원)의 보낸·받은 요청 목록. 생성 시각·ID 내림차순 20개씩. 현재 활성 소속 기준이라 이전 매장 요청은 나오지 않는다.
     */
    public Page list(long userId, RequestBox box, String cursorValue) {
        ActiveMembership me = membershipQuery.findActiveByUser(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!CREW.equals(me.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        RequestCursor cursor = RequestCursor.decode(cursorValue);
        Limit limit = Limit.of(PAGE_SIZE + 1);
        long membershipId = me.membershipId();

        List<SubstituteRequest> rows = switch (box) {
            case SENT -> cursor == null
                    ? requestRepository.findSentFirstPage(membershipId, limit)
                    : requestRepository.findSentAfter(membershipId, cursor.createdAt(), cursor.id(), limit);
            case RECEIVED -> cursor == null
                    ? requestRepository.findReceivedFirstPage(membershipId, limit)
                    : requestRepository.findReceivedAfter(membershipId, cursor.createdAt(), cursor.id(), limit);
        };
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<SubstituteRequest> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, String> myResponses = box == RequestBox.RECEIVED && !page.isEmpty()
                ? recipientRepository.findByTargetMembershipIdAndRequestIdIn(membershipId,
                                page.stream().map(SubstituteRequest::getId).toList()).stream()
                        .collect(Collectors.toMap(RequestRecipient::getRequestId, r -> r.getResponse().name()))
                : Map.of();

        Names names = new Names(membershipQuery);
        List<Summary> items = page.stream()
                .map(r -> new Summary(r.getId(), r.getStatus().name(), nameOrNull(r.getTerminalReason()),
                        new ShiftRef(r.getShiftId(), r.getSourceStartsAt(), r.getSourceEndsAt(), null),
                        names.ref(r.getRequesterMembershipId()), names.ref(r.getCandidateMembershipId()),
                        myResponses.get(r.getId()), r.getCreatedAt()))
                .toList();
        String nextCursor = hasMore
                ? new RequestCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new Page(items, nextCursor, hasMore);
    }

    private static RequestRelation relationOf(SubstituteRequest request, ActiveMembership me,
                                              RequestRecipient myRecipient) {
        long membershipId = me.membershipId();
        if (request.isRequester(membershipId)) {
            return RequestRelation.REQUESTER;
        }
        if (request.isCandidate(membershipId)) {
            return RequestRelation.CANDIDATE;
        }
        if (myRecipient != null) {
            return RequestRelation.TARGET;
        }
        if (MANAGER.equals(me.role())) {
            return RequestRelation.MANAGER;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private static String nameOrNull(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** 한 응답 안에서 같은 소속의 이름을 한 번만 조회한다. */
    private static final class Names {

        private final Map<Long, String> cache = new HashMap<>();
        private final Function<Long, String> loader;

        Names(MembershipQuery membershipQuery) {
            this.loader = membershipQuery::displayNameOf;
        }

        String of(long membershipId) {
            return cache.computeIfAbsent(membershipId, loader);
        }

        MemberRef ref(Long membershipId) {
            return membershipId == null ? null : new MemberRef(membershipId, of(membershipId));
        }
    }
}
