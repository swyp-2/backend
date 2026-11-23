-- App Review 크루원 가입용 실제 매장. 코드는 심사 메모에만 제공한다.
-- 매니저 계정은 Apple 로그인으로 생성해야 하므로 이 매장에는 넣지 않는다.
WITH review_store AS (
    INSERT INTO stores (id, name, timezone, state, created_at)
    VALUES (nextval('stores_seq'), 'BATON 심사용 매장', 'Asia/Seoul', 'ACTIVE', now())
    RETURNING id
)
INSERT INTO invite_codes (id, store_id, code, created_at)
SELECT nextval('invite_codes_seq'), id, 'BATON826', now()
FROM review_store;
