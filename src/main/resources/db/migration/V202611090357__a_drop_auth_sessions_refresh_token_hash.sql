-- refresh 토큰 방식을 쓰지 않는다. 로그인 시 발급하는 단일 액세스 토큰(14일, 연장 없음)의 sid가 auth_sessions.id를 가리킨다.
-- 컬럼에 걸린 auth_sessions_hash_format_ck, auth_sessions_refresh_token_hash_uk도 함께 삭제된다.
ALTER TABLE auth_sessions DROP COLUMN refresh_token_hash;
