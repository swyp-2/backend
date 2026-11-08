-- 마이그레이션 이름: V{yyyyMMddHHmm(Asia/Seoul)}__{a|b}_{설명}.sql
-- A·B의 머지 순서가 엇갈려 적용 순서가 바뀔 수 있어 spring.flyway.out-of-order=true.

-- 멱등성 기록 ---------------------------------------------------------
-- 상태를 바꾸는 POST의 중복 제출(Idempotency-Key 헤더)에 첫 처리 결과를 그대로 돌려주기 위한 기록.
-- 첫 요청과 같은 트랜잭션에서 삽입되므로 처리 실패(롤백) 시 기록도 남지 않는다.
-- 보존 24시간. 응답 본문에 개인 정보가 있을 수 있어 만료 정리 + 탈퇴 시 사용자 기록 삭제.
CREATE TABLE idempotency_records (
    id                  bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             bigint       NOT NULL, -- users.id. 소유 경계 때문에 FK를 걸지 않는다
    scope               varchar(40)  NOT NULL,
    idempotency_key     varchar(100) NOT NULL,
    request_fingerprint varchar(64)  NOT NULL, -- SHA-256(scope + 요청 내용) hex
    response_body       jsonb,
    created_at          timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    CONSTRAINT idempotency_records_scope_ck       CHECK (scope IN (
        'SUBSTITUTE_REQUEST_CREATE', 'SUBSTITUTE_REQUEST_ACCEPT', 'SUBSTITUTE_REQUEST_REJECT',
        'SUBSTITUTE_REQUEST_CANCEL', 'SUBSTITUTE_REQUEST_WITHDRAW', 'SUBSTITUTE_REQUEST_APPROVE',
        'SUBSTITUTE_REQUEST_FINAL_REJECT', 'SHIFT_BATCH_CREATE')),
    CONSTRAINT idempotency_records_key_format_ck  CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,100}$'),
    CONSTRAINT idempotency_records_fingerprint_ck CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_records_expiry_ck      CHECK (expires_at > created_at),
    -- 같은 사용자·같은 작업 종류의 키는 하나. 탈퇴 시 user_id 기준 삭제도 이 인덱스를 쓴다
    CONSTRAINT idempotency_records_user_scope_key_uk UNIQUE (user_id, scope, idempotency_key)
);
-- 만료 기록 정리
CREATE INDEX idempotency_records_expires_idx ON idempotency_records (expires_at);
