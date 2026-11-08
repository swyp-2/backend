package com.baton.store.domain;

/**
 * 매장 폐쇄는 마지막 매니저 특수 처리와 함께 MVP 범위 밖이라 ACTIVE만 둔다.
 */
public enum StoreState {
    ACTIVE
}
