package com.baton.store.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    Optional<InviteCode> findByStoreIdAndInvalidatedAtIsNull(Long storeId);

    Optional<InviteCode> findByCodeAndInvalidatedAtIsNull(String code);

    /**
     * 코드가 이미 있으면(invite_codes_code_uk) 아무것도 하지 않고 0을 돌려준다. 예외가 나지 않아 트랜잭션이 깨지지 않으므로
     * 같은 트랜잭션 안에서 새 코드로 재시도할 수 있다. 같은 코드를 다른 트랜잭션이 삽입 중이면 그 결과를 기다린다.
     * id는 시퀀스 값을 그대로 쓴다(Hibernate pooled optimizer가 받은 값과 겹치지 않는다).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO invite_codes (id, store_id, code, created_at)
            VALUES (nextval('invite_codes_seq'), :storeId, :code, :now)
            ON CONFLICT (code) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("storeId") long storeId, @Param("code") String code, @Param("now") Instant now);
}
