package com.baton.substitute.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestEventRepository extends JpaRepository<RequestEvent, Long> {

    List<RequestEvent> findByRequestIdOrderByOccurredAtAscIdAsc(Long requestId);
}
