package com.baton.substitute.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestRecipientRepository extends JpaRepository<RequestRecipient, Long> {

    List<RequestRecipient> findByRequestIdOrderByIdAsc(Long requestId);

    /** 요청의 지정 대상 한 명. 요청 행을 잠근 뒤 읽는다(지정 대상 응답은 요청 잠금 아래에서만 바뀐다). */
    Optional<RequestRecipient> findByRequestIdAndTargetMembershipId(Long requestId, Long targetMembershipId);

    /** 전원 거절 판정: 아직 응답하지 않은 대상이 남았는지. */
    boolean existsByRequestIdAndResponse(Long requestId, RecipientResponse response);

    /** 소속 해제 정리: 응답 불가가 아닌 대상(미응답·수락·거절)이 남았는지. */
    boolean existsByRequestIdAndResponseNot(Long requestId, RecipientResponse response);

    /** 받은 요청 목록의 본인 응답 상태. */
    List<RequestRecipient> findByTargetMembershipIdAndRequestIdIn(Long targetMembershipId, Collection<Long> requestIds);
}
