package com.baton.common.idempotency;

/**
 * 멱등성 키가 적용되는 작업 종류. 같은 키라도 작업 종류가 다르면 별개의 기록이다.
 * 값을 추가하면 idempotency_records_scope_ck 제약도 새 마이그레이션으로 바꿔야 한다.
 */
public enum IdempotencyScope {
    SUBSTITUTE_REQUEST_CREATE,
    SUBSTITUTE_REQUEST_ACCEPT,
    SUBSTITUTE_REQUEST_REJECT,
    SUBSTITUTE_REQUEST_CANCEL,
    SUBSTITUTE_REQUEST_WITHDRAW,
    SUBSTITUTE_REQUEST_APPROVE,
    SUBSTITUTE_REQUEST_FINAL_REJECT,
    SHIFT_BATCH_CREATE
}
