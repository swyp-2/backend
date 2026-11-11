package com.baton.integration.stub;

import com.baton.integration.ActiveMembership;
import com.baton.integration.StoreManagerQuery;
import com.baton.member.domain.Role;
import com.baton.store.domain.Membership;
import com.baton.store.domain.MembershipState;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * A의 {@link StoreManagerQuery} 구현이 들어오기 전까지 쓰는 임시 구현(B 작성).
 * A의 {@link Membership} 엔티티를 읽기만 하며 A 코드는 수정하지 않는다.
 */
public class StoreManagerQueryStub implements StoreManagerQuery {

    private final EntityManager entityManager;

    public StoreManagerQueryStub(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActiveMembership> findActiveManagers(long storeId) {
        return entityManager.createQuery("""
                        select m from Membership m
                        where m.storeId = :storeId and m.role = :role and m.state = :state
                        order by m.id
                        """, Membership.class)
                .setParameter("storeId", storeId)
                .setParameter("role", Role.MANAGER)
                .setParameter("state", MembershipState.ACTIVE)
                .getResultList().stream()
                .map(m -> new ActiveMembership(m.getId(), m.getStoreId(), m.getUserId(), m.getRole().name()))
                .toList();
    }
}
