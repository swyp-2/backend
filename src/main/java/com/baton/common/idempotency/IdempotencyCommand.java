package com.baton.common.idempotency;

/**
 * @param userId  인증된 사용자 id (요청 본문 값이 아니라 인증 정보에서 가져온다)
 * @param key     Idempotency-Key 헤더 값. 없으면 null
 * @param payload 요청 내용(경로 변수 + 본문). 같은 키에 다른 내용이 오면 거부하는 기준이 된다.
 *                필드 순서가 고정되도록 record를 쓴다 (Map은 순서가 바뀌면 다른 요청으로 본다)
 */
public record IdempotencyCommand(long userId, IdempotencyScope scope, String key, Object payload) {
}
