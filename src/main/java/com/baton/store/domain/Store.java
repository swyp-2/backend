package com.baton.store.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 생성자 개인 연결(created_by)은 두지 않는다. 최초 매니저 소속으로 판단한다.
 */
@Getter
@Entity
@Table(name = "stores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stores_seq")
    @SequenceGenerator(name = "stores_seq", sequenceName = "stores_seq", allocationSize = 50)
    private Long id;

    @Column(name = "name", nullable = false, length = 15)
    private String name;

    @Column(name = "timezone", nullable = false, length = 40)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private StoreState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** name은 호출 측에서 앞뒤 공백 제거·길이 검증을 마친 값. */
    public static Store open(String name, Instant now) {
        Store store = new Store();
        store.name = name;
        store.timezone = DEFAULT_TIMEZONE;
        store.state = StoreState.ACTIVE;
        store.createdAt = now;
        return store;
    }
}
