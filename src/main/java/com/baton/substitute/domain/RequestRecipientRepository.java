package com.baton.substitute.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestRecipientRepository extends JpaRepository<RequestRecipient, Long> {

    List<RequestRecipient> findByRequestIdOrderByIdAsc(Long requestId);

    /** 받은 요청 목록의 본인 응답 상태. */
    List<RequestRecipient> findByTargetMembershipIdAndRequestIdIn(Long targetMembershipId, Collection<Long> requestIds);
}
