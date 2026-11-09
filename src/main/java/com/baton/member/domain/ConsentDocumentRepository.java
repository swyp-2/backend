package com.baton.member.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsentDocumentRepository extends JpaRepository<ConsentDocument, Long> {

    /** kind별로 effective_at <= now 인 문서 중 최신 버전만. 문서 종류는 하드코딩하지 않는다. */
    @Query("""
            select d from ConsentDocument d
            where d.effectiveAt <= :now
              and d.version = (select max(d2.version) from ConsentDocument d2
                               where d2.kind = d.kind and d2.effectiveAt <= :now)
            order by d.id
            """)
    List<ConsentDocument> findCurrent(@Param("now") Instant now);
}
