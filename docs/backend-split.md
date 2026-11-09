# 백엔드 분담

## A: 회원·매장·근무
POST /auth/{provider}/complete, POST /auth/logout, GET /me, GET /consent-documents,
POST /me/consents, PUT /me/onboarding/name, PUT /me/onboarding/role, POST /stores, GET /stores/{store_id},
GET /stores/{store_id}/invite-code, POST /memberships/join, GET /stores/{store_id}/members,
GET /stores/{store_id}/shifts, GET /shifts/{shift_id}, POST /shift-batches/preview, POST /shift-batches,
DELETE /shifts/{shift_id}, POST /stores/{store_id}/leave, DELETE /me

## B: 대타·알림·공통
/substitute-requests 하위 9개, /me/substitute-requests, /me/notifications/open, GET /me/notifications,
GET /me/notification-indicator, 만료·알림 정리 스케줄러, common(응답·에러·멱등성), Docker·EC2 배포

## 공통 규칙
- 응답: {"data": ..., "meta": {"server_time"}} / 에러: {"error": {"code","message","field_errors","retryable"}, "current_state"?, "existing_request_id"?}
  - existing_request_id: ACTIVE_REQUEST_EXISTS(409)일 때만 나오는 진행 중 요청 id (2026-11-09 B 추가). 그 밖에는 키 자체가 없다
- 응답 본문이 없는 성공은 204 No Content (본문·meta 없음). 예: POST /auth/logout
- 계정당 활성 매장 소속은 1개다 (DB 부분 유니크 memberships_user_active_uk). 이미 활성 소속이 있으면
  매장 생성·참여는 409 ALREADY_IN_STORE
- 잠금 순서: membership → shift → substitute_request
- 승인(B)은 후보 membership 행을 잠근 뒤 ACTIVE인지 확인한다. 나가기·탈퇴(A)도 같은 행을 잠근다
- 멱등성: Idempotency-Key 헤더 + IdempotencyService (B 제공)

## 인터페이스 (com.baton.integration)

B가 구현, A가 호출:

    public interface SubstituteRequestLifecycle {
        // 근무 삭제 트랜잭션 안에서 호출. 활성 요청을 삭제 사유로 종료. 종료한 요청 id 반환
        Optional<Long> closeBySourceDeleted(long shiftId, long actorMembershipId);
        // 나가기·탈퇴 트랜잭션 안에서 호출. 요청자/후보/지정 대상 정리 (기능명세 SUB-10 규칙)
        void cleanupOnMembershipLeave(long membershipId);
        // 근무 상세(SH-01/02)에 붙일 활성 요청 요약. 없으면 empty
        Optional<ActiveRequestSummary> findActiveByShift(long shiftId, long viewerMembershipId);
        // 탈퇴 파기: 요청·이벤트 스냅샷의 개인 정보 제거, 본인 수신 알림 삭제
        void anonymizeFor(long membershipId);
    }
    public record ActiveRequestSummary(long requestId, String status, boolean viewerIsRequester,
                                       boolean pendingManagerApproval) {}

A가 구현, B가 호출:

    // 실제 파일: com.baton.integration.ShiftAssignment / ShiftSnapshot (구현은 A가 근무 작업에서 추가)
    // - reassign: 호출자 트랜잭션 필수. 근무 행 잠금, 성공 시 version +1, shift_events(REASSIGNED) 기록
    // - getSnapshot: 잠그지 않음. 삭제된 근무도 deleted=true로 반환, 없는 근무면 NOT_FOUND
    public interface ShiftAssignment {
        // 승인 트랜잭션 안에서 호출. 버전 불일치·삭제·담당자 불일치면 BusinessException(SHIFT_STATE_CONFLICT)
        void reassign(long shiftId, long fromMembershipId, long toMembershipId, long expectedVersion);
        // 요청 생성 검증과 스냅샷용
        ShiftSnapshot getSnapshot(long shiftId);
        // [2026-11-09 B 추가] getSnapshot + 근무 행 잠금(FOR UPDATE). 호출자 트랜잭션 필수.
        // 대타 요청 생성이 근무 삭제와 동시에 일어날 때 순서를 맞추기 위함. 없는 근무면 NOT_FOUND
        ShiftSnapshot lockSnapshot(long shiftId);
    }
    public record ShiftSnapshot(long shiftId, long storeId, long assigneeMembershipId,
                                Instant startsAt, Instant endsAt, long version, boolean deleted) {}

    public interface MembershipQuery {
        // 활성 소속이 아니면 BusinessException(FORBIDDEN)
        ActiveMembership requireActive(long userId, long storeId);
        // [2026-11-09 B 추가] 사용자의 현재 활성 소속(MVP는 1개). 없으면 empty.
        // 경로에 매장 id가 없는 GET /me/substitute-requests용. A의 MembershipGuard에 이미 같은 메서드가 있어 추가 구현 없음
        Optional<ActiveMembership> findActiveByUser(long userId);
        // 잠금 버전. 승인·수락 시 후보 소속 재확인용
        Optional<ActiveMembership> lockActiveById(long membershipId);
        // 대타 후보: 같은 매장 ACTIVE CREW, exclude 제외
        List<ActiveMembership> findCrewCandidates(long storeId, long excludeMembershipId);
        // 표시용 이름. 탈퇴/연결 해제면 "탈퇴한 구성원"
        String displayNameOf(long membershipId);
    }
    public record ActiveMembership(long membershipId, long storeId, long userId, String role) {}
## B 데이터 모델: 대타·알림 (V202611092000__b_substitute_notification.sql)

### 테이블
| 테이블 | 용도 | 주요 제약 |
| --- | --- | --- |
| substitute_requests | 대타 요청 1건. 원본 근무 스냅샷(source_starts_at/ends_at/version) 보관 | 근무당 진행 중(WAITING/PENDING_MANAGER) 1건 부분 유니크, 요청자·후보는 같은 매장 소속(복합 FK), @Version |
| request_recipients | 지정 대상. response = PENDING/ACCEPTED/DECLINED/UNAVAILABLE | (request_id, target_membership_id) 유니크 |
| request_events | 진행 기록(요청·수락·거절·취소·철회·승인·만료·삭제·소속 해제) | 추가만 함. 시스템 처리면 actor NULL |
| notifications | 앱 내부 알림(이벤트 × 수신자) | (event_id, recipient_membership_id) 유니크, expires_at = created_at + 168시간 |

PK는 A와 같은 방식(테이블별 시퀀스, INCREMENT 50).

### 요청 상태와 종료 사유
- 진행 중: WAITING, PENDING_MANAGER / 종료: APPROVED, REJECTED, ALL_DECLINED, CANCELLED, WITHDRAWN, EXPIRED,
  CANCELLED_SOURCE_CHANGED. 종료 상태는 다시 열지 않는다
- terminal_reason은 사용자 행동이 아닌 종료에만 둔다

| 사건 | status | terminal_reason |
| --- | --- | --- |
| 근무 삭제 | CANCELLED_SOURCE_CHANGED | SHIFT_DELETED |
| 요청자 소속 해제 | CANCELLED | REQUESTER_LEFT |
| 승인 대기 후보 소속 해제 | WITHDRAWN | CANDIDATE_LEFT |
| 응답 가능 대상 0명 | CANCELLED | NO_ACTIVE_TARGET |

근무 삭제 표기는 정책서(CANCELLED_SOURCE_CHANGED)와 기능명세서(SOURCE_CHANGED + 삭제 사유)가 달라
"상태 CANCELLED_SOURCE_CHANGED + 사유 SHIFT_DELETED"로 정했다 (2026-11-09, B).

### 기타 결정
- 후보(candidate_membership_id)는 종료 후에도 지우지 않는다. 직전 후보에게 결과 알림을 보내고 진행 기록에 쓰기 위해서다
- 알림에는 store_id를 두지 않는다 (수신자 소속에서 알 수 있음)
- 탈퇴해도 요청·이벤트의 membership_id는 남긴다. A가 memberships.user_id를 비워 개인과의 연결이 끊어지기 때문이다.
  anonymizeFor는 본인이 받은 알림만 삭제한다 → **A 확인 필요**

### ShiftAssignment 임시 구현 (com.baton.integration.stub)
- A 구현이 없을 때만 등록된다 (IntegrationStubConfig의 @Bean @ConditionalOnMissingBean).
  A가 ShiftAssignment 구현 빈을 추가하면 자동으로 빠지므로 스텁을 지우지 않아도 된다
- getSnapshot: Shift 조회 / reassign: 근무 행 비관적 잠금 → 버전 비교 → Shift.reassign()
- 실제 구현과의 차이: shift_events(REASSIGNED)를 남기지 않는다. A 구현에서 남겨 주어야 한다

## B API: 대타 요청 생성·조회 (2026-11-09)

| 메서드·경로 | 누가 | 응답 data |
| --- | --- | --- |
| POST /substitute-requests (Idempotency-Key 필수) | 근무 담당 크루원 | 만든 요청의 상세(아래 상세와 같음) |
| GET /substitute-requests/{id} | 같은 매장의 요청자·지정 대상·매니저 | 상세 |
| GET /me/substitute-requests?box=sent\|received&cursor= | 크루원(현재 활성 소속 기준) | {items, next_cursor, has_more} |

요청 본문: `{"shift_id": 1, "target_membership_ids": [2, 3]}`

상세 필드: id, status, terminal_reason, version, created_at, closed_at,
shift{shift_id, starts_at, ends_at, deleted}, requester{membership_id, display_name}, candidate(없으면 null),
my_relation(REQUESTER/CANDIDATE/TARGET/MANAGER), recipients[{membership_id, display_name, response}],
allowed_actions(cancel/accept/reject/withdraw/approve/final_reject), events[{kind, actor(시스템이면 null), occurred_at}]
- shift의 시각은 요청 생성 시점 스냅샷. 근무가 삭제되면 deleted=true ("삭제 전 정보"로 표시)
- recipients는 요청자·매니저에게 전체, 지정 대상·후보에게는 본인 것만
- allowed_actions는 표시용. 종료됐거나 근무 시작 시각이 지난 요청은 빈 배열. 실제 변경 API가 다시 검사한다

목록 항목: id, status, terminal_reason, shift{shift_id, starts_at, ends_at, deleted=null}, requester, candidate,
my_response(받은 요청만, 보낸 요청은 null), created_at. 정렬은 생성 시각·id 내림차순 20개, next_cursor는 불투명 문자열

생성 에러
| 상황 | 에러 |
| --- | --- |
| 매장 소속 아님·매니저·본인 담당 근무 아님 | 403 FORBIDDEN |
| 없는 근무 | 404 NOT_FOUND |
| 삭제된 근무 | 409 SHIFT_STATE_CONFLICT |
| 이미 시작한 근무 | 409 REQUEST_EXPIRED |
| 대상 0명·중복·본인·매니저·다른 매장 | 422 VALIDATION_FAILED (field: target_membership_ids) |
| 같은 근무에 진행 중 요청 있음 | 409 ACTIVE_REQUEST_EXISTS + existing_request_id |

조회 에러: 요청 없음 404 / 관계없는 사람·다른 매장 403 / 매니저·소속 없음의 목록 조회 403 / box·cursor 형식 오류 422

생성 트랜잭션의 잠금 순서: idempotency_records → 요청자·대상 membership(id 오름차순, lockActiveById) →
shift(lockSnapshot) → substitute_request. 소속을 잠그므로 A의 나가기·탈퇴와 동시에 일어나도 정리 대상에서 빠지지 않는다.

A에게 영향: Lifecycle.findActiveByShift가 실제 값을 돌려준다 (근무 상세 SH-01/02에서 사용).

## Swagger(OpenAPI) 문서 (2026-11-09 B)
- 주소: /swagger-ui.html (JSON은 /v3/api-docs)
- **필드명 수정**: 그동안 문서의 필드명이 camelCase(storeId 등)로 나왔지만 실제 응답은 snake_case였다.
  springdoc의 스키마 생성기(swagger-core)가 Jackson 2를 따로 써서 Spring Boot의 Jackson 3 설정이 전달되지 않기 때문이다.
  config/OpenApiConfig에 snake_case ModelResolver 빈을 추가해 A의 API 문서도 함께 맞췄다 → **A 확인 필요**
- 대타 요청 API는 @Tag("대타 요청"), @Operation(요약·에러 코드), 필수 헤더, 고유 스키마 이름
  (SubstituteRequestDetail 등)을 달았다. 레코드 이름이 일반적이면(Detail, Page) 다른 기능과 문서 스키마가 겹치므로
  @Schema(name = ...)로 고유하게 지정하는 것을 권장
