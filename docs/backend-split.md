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

    // [2026-11-10 B 추가] 실제 파일: com.baton.integration.StoreManagerQuery → **A 구현 요청**
    // 대타 처리 결과 알림의 "소속 매니저" 수신자 계산용. A 구현 전까지 B의 스텁(StoreManagerQueryStub)이 쓰인다
    public interface StoreManagerQuery {
        // 매장의 ACTIVE MANAGER 소속, id 오름차순. 잠그지 않음. 없으면 빈 목록
        List<ActiveMembership> findActiveManagers(long storeId);
    }

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

## B API: 대타 요청 상태 전이 (2026-11-10)

모두 `POST /substitute-requests/{id}/{행동}`, 본문 없음, Idempotency-Key 필수, 응답 data는 처리 후 최신 상세.

| 행동 | 누가 | 상태 | 결과 | 결과 알림 |
| --- | --- | --- | --- | --- |
| accept | 지정 크루원(거절했던 대상 포함) | WAITING | PENDING_MANAGER, 후보 확정 | 요청자·후보·매니저 |
| reject | 미응답 지정 크루원 | WAITING | 그대로, 미응답 대상이 없으면 ALL_DECLINED | 요청자·매니저 |
| cancel | 요청자 | WAITING·PENDING_MANAGER | CANCELLED | 요청자·직전 후보·매니저 |
| withdraw | 현재 후보 | PENDING_MANAGER | WITHDRAWN | 요청자·후보·매니저 |
| approve | 소속 매니저 | PENDING_MANAGER | APPROVED + 근무 담당자 → 후보(ShiftAssignment.reassign) | 요청자·후보·매니저 |
| final-reject | 소속 매니저 | PENDING_MANAGER | REJECTED | 요청자·후보·매니저 |

- 알림은 지금 활성 소속인 사람만, 같은 사람은 1건. "매니저"는 그 매장의 활성 매니저 전원.
  단 행동한 본인은 받지 않는다(2026-11-11 변경, 아래 "빨간 점" 절 참고). 위 표의 수신자에서 행위자를 뺀다
- 진행 기록 kind: CANDIDATE_ACCEPTED, TARGET_DECLINED(전원 거절이면 after_status=ALL_DECLINED), REQUEST_CANCELLED,
  CANDIDATE_WITHDRAWN, MANAGER_APPROVED, MANAGER_REJECTED

에러
| 상황 | 에러 |
| --- | --- |
| 관계·역할이 아님(지정 대상 아님, 요청자 아님, 후보 아님, 매니저 아님), 다른 매장 | 403 FORBIDDEN |
| 없는 요청 | 404 NOT_FOUND |
| 이미 처리됨·종료됨·다른 후보 확정·후보가 매장을 나감, 처리하는 사이 다른 사람이 수락함 | 409 REQUEST_STATE_CONFLICT + current_state |
| 근무 시작 시각 이후(스케줄러가 EXPIRED로 바꾸기 전 포함), 6개 행동 모두 | 409 REQUEST_EXPIRED + current_state |
| 승인 시 근무 삭제·담당자·버전 변경 / 후보에게 완전히 같은 근무가 이미 있음(shifts_identical_live_uk) | 409 SHIFT_STATE_CONFLICT |
| 키 누락·형식 오류 / 같은 키를 다른 요청에 사용 | 422 VALIDATION_FAILED / IDEMPOTENCY_KEY_REUSED |

결정 (2026-11-10 B)
- 시작 시각 이후에는 수락·승인뿐 아니라 6개 행동 모두 REQUEST_EXPIRED (allowed_actions가 빈 배열인 것과 맞춤)
- 요청 본문에 expected_version을 받지 않는다. 잠금 후 상태·관계·시작 시각을 서버가 다시 검사한다
- 후보 중복 근무 충돌은 새 에러 코드 없이 SHIFT_STATE_CONFLICT + 메시지 "후보에게 이미 같은 시간의 근무가 있어요..."

잠금 순서: idempotency_records → 요청자·후보·행위 크루원 membership(id 오름차순, lockActiveById) →
(승인만) shift(lockSnapshot) → substitute_request(FOR UPDATE). 매니저 본인 소속은 잠그지 않는다.

A에게 영향
- StoreManagerQuery 구현 요청 (위 인터페이스 절)
- ShiftAssignment.reassign 구현 시: 후보에게 완전히 같은 근무가 있으면 shifts_identical_live_uk 위반이 난다.
  B는 예외 원인 체인에 인덱스 이름이 있으면 SHIFT_STATE_CONFLICT로 바꾸므로, A 구현은 이 예외를 삼키거나 다른 메시지로
  감싸지 말고 그대로 던져 주면 된다

## Swagger(OpenAPI) 문서 (2026-11-09 B)
- 주소: /swagger-ui.html (JSON은 /v3/api-docs)
- **필드명 수정**: 그동안 문서의 필드명이 camelCase(storeId 등)로 나왔지만 실제 응답은 snake_case였다.
  springdoc의 스키마 생성기(swagger-core)가 Jackson 2를 따로 써서 Spring Boot의 Jackson 3 설정이 전달되지 않기 때문이다.
  config/OpenApiConfig에 snake_case ModelResolver 빈을 추가해 A의 API 문서도 함께 맞췄다 → **A 확인 필요**
- 대타 요청 API는 @Tag("대타 요청"), @Operation(요약·에러 코드), 필수 헤더, 고유 스키마 이름
  (SubstituteRequestDetail 등)을 달았다. 레코드 이름이 일반적이면(Detail, Page) 다른 기능과 문서 스키마가 겹치므로
  @Schema(name = ...)로 고유하게 지정하는 것을 권장

## A API: 근무 일괄 등록 (2026-11-10 A)

| 메서드·경로 | 누가 | 응답 data |
| --- | --- | --- |
| POST /shift-batches/preview | 본문 store_id 매장의 매니저 | {preview_token, create_count, duplicate_count, total_count, items[{membership_id, display_name, local_date, starts_at, ends_at, duplicate}]} |
| POST /shift-batches (Idempotency-Key 필수) | 같은 매니저 | {created_count, duplicate_count, shift_ids[]} (실제로 저장한 건만) |

요청 본문: `{"store_id", "assignee_membership_ids", "starts_at", "ends_at", "repeat": {"enabled", "start_date", "end_date", "weekdays"}}`,
최종 등록은 여기에 `"preview_token"`을 더한다(미리보기와 같은 입력 그대로).
- 미리보기는 DB에 쓰지 않는다. preview_token은 서버 서명 불투명 문자열(30분, DB 저장 없음)
- starts_at·ends_at은 분 단위만(초·나노초가 있으면 422)
- 전부 중복이면 미리보기는 200 + create_count 0, 최종 등록은 422 VALIDATION_FAILED("모두 이미 등록된 근무예요.")
- 409 PREVIEW_STALE: 토큰 만료·변조, 입력이 미리보기와 다름, 미리보기 이후 중복 상태가 바뀜(누가 그사이 같은 근무를 등록·삭제).
  입력은 유지하고 미리보기를 다시 호출한다. 저장은 전부 아니면 0건
- 403: 매니저가 아님 / 422: 다른 매장·나간 구성원 포함, 입력 오류, 토큰 누락
- 최종 등록 트랜잭션 잠금 순서: idempotency_records → 담당 membership(FOR SHARE, id 오름차순) → shifts(부분 유니크).
  나가기·탈퇴의 membership FOR UPDATE와 직렬화된다
- 등록한 근무마다 shift_events(CREATED, actor = 매니저 membership, after_value = {assignee_membership_id, starts_at, ends_at})

## B API: 빨간 점(알림) (2026-11-11 B)

**기획 변경(Figma 기준, 2026-11-11 B·팀 결정):** 알림 목록 화면(NT-01)과 `GET /me/notifications`는 만들지 않는다.
빨간 점을 두 단계로 표시한다. 기능명세서 v3.2(NTF-01~05)와 다르므로 PM 문서 반영 필요.

| 점 | 켜지는 조건 | 지우는 호출 |
| --- | --- | --- |
| 하단 바 "요청" 탭 | 최근 7일 보관 중 미읽음 알림(read_at NULL)이 있음 | 요청 탭 진입 시 `POST /me/notifications/open` |
| 요청 카드 | 그 요청에 대한 보관 중 미확인 알림(checked_at NULL)이 있음 | 카드를 눌러 상세를 열 때 `POST /me/substitute-requests/{id}/check` |

| 메서드·경로 | 응답 |
| --- | --- |
| GET /me/notification-indicator | 200 {has_unread} |
| POST /me/notifications/open | 204. 호출 시점까지 저장된 본인 알림 전부 읽음. 이후 도착분은 미읽음. 카드 점은 그대로 |
| POST /me/substitute-requests/{id}/check | 204. 그 요청의 본인 알림 확인(+미읽음이면 읽음). 관계없는·없는 요청이면 변경 없음 |
| GET /me/substitute-requests (기존) | 목록 항목에 `unchecked`(카드 점) 추가 |

- 모두 현재 활성 소속 기준. 활성 소속이 없으면 403. Idempotency-Key는 받지 않는다(재시도해도 결과 같음)
- 계약 초안의 visit_id·read_through_token은 두지 않는다. "그 문장 실행 시점에 저장이 끝난 알림"만 바꾸므로
  시각·id 워터마크가 필요 없다(시퀀스 id·생성 시각은 커밋 순서와 다를 수 있음)
- 생성 후 정확히 168시간이 되면 두 점 계산에서 모두 빠진다
- 스키마: V202611111000__b_notification_checked.sql — notifications.checked_at 추가,
  CHECK(checked_at IS NULL OR read_at IS NOT NULL), 미읽음·미확인 부분 인덱스
- 잠금: 읽음·확인 UPDATE는 알림 행을 id 오름차순으로 잠근 뒤 바꾼다(같은 사람의 open·check 동시 실행 교착 방지).
  대타 처리는 알림을 INSERT만 하므로 기존 잠금 순서(idempotency → membership → shift → substitute_request)와 겹치지 않는다
- 행동한 본인은 결과 알림을 받지 않는다(2026-11-11 결정, 기능명세서 NTF-04 "행위자 본인도 포함"과 다름).
  매니저가 여럿이면 처리한 매니저만 빠진다. 새 요청 알림(지정 대상에게)은 원래 본인이 받지 않는다

## A API: 근무 삭제 + ShiftAssignment 실제 구현 (2026-11-11 A)

| 메서드·경로 | 누가 | 응답 data |
| --- | --- | --- |
| DELETE /shifts/{shift_id}?expected_version={n} | 그 근무 매장의 매니저(과거 근무 포함) | {shift_id, deleted: true, closed_request_id(없으면 null)} |

- 한 트랜잭션: 근무 FOR UPDATE → 상태·버전 확인 → 소프트 삭제(version +1) → shift_events(DELETED, actor = 매니저,
  before_value = {assignee_membership_id, starts_at, ends_at}) → Lifecycle.closeBySourceDeleted(shiftId, 매니저 membership).
  B 처리에서 예외가 나면 삭제도 전부 롤백된다
- 에러: expected_version 누락·형식 오류 422 VALIDATION_FAILED / 매니저 아님·다른 매장 403 / 없는 근무 404 /
  409 SHIFT_STATE_CONFLICT + current_state ("DELETED": 이미 삭제됨, "CHANGED": 버전·담당자 바뀜)
- **current_state는 문자열만 담는다.** 409를 받으면 프론트는 최신 근무를 다시 조회한다(정식 동작). DELETED면 상세가 404이므로 날짜 근무표로 돌아간다
- 권한(403)을 상태(409)보다 먼저 검사한다. 권한 없는 사람에게 근무 상태를 알려 주지 않기 위해서다
- 반복 등록한 다른 날짜 근무는 독립 행이라 영향이 없다(B04)
- 멱등 키는 받지 않는다. expected_version으로 재시도·중복 제출이 409가 된다(B20)

ShiftAssignment (com.baton.shift.application.ShiftAssignmentService)
- 이 빈이 생겨 integration.stub.ShiftAssignmentStub은 더 이상 등록되지 않는다. 스텁 계약 테스트는 스텁을 직접 만들어 유지했다.
  **스텁 삭제 여부는 B가 결정**
- reassign: 잠금 순서를 지키려고 to 소속(lockActiveById)을 근무보다 먼저 잠근다(B가 이미 잠갔으면 바로 통과) →
  근무 FOR UPDATE → 삭제됨(DELETED) / 버전 불일치·현재 담당자 != from·to == from·to가 비활성 또는 다른 매장(CHANGED)이면
  SHIFT_STATE_CONFLICT → 담당자 변경·flush(version +1) → shift_events(REASSIGNED, before/after = {assignee_membership_id})
- shifts_identical_live_uk 위반은 감싸지 않고 그대로 던진다
- REASSIGNED 이벤트의 request_id·actor_membership_id는 인터페이스로 전달되지 않아 NULL이다 → **B와 합의 필요**
  (필요하면 reassign에 requestId·actorMembershipId 인자 추가)

integration.stub.NoopSubstituteRequestLifecycle: B 구현이 없을 때만 등록되는 빈 구현(IntegrationStubConfig @Bean). 지금은 B 구현이 있어 쓰이지 않는다

## A API: 매장 나가기·계정 탈퇴 (2026-11-11 A)

| 메서드·경로 | 누가 | 응답 |
| --- | --- | --- |
| POST /stores/{store_id}/leave | 그 매장의 본인 활성 소속 | 204 |
| DELETE /me | 본인 | 204 |

- 공통 사전 검사(한 트랜잭션 안): user FOR UPDATE → 본인 활성 membership FOR UPDATE → 끝나지 않은 본인 배정 근무
  (deleted_at IS NULL AND ends_at > now, 예정 + 진행 중)가 있으면 409 FUTURE_SHIFT_REMAINS, 아무것도 바꾸지 않는다.
  **current_state 없음**(남은 개수·시각은 본문에 넣지 않는다. 프론트는 내 근무 조회로 확인)
- 마지막 매니저 특수 처리는 없다(U-02, 분기 위치에 TODO만)
- 나가기: 사전 검사 → `Lifecycle.cleanupOnMembershipLeave` → membership LEFT → user 단계 STORE_REQUIRED(이름·역할 유지).
  계정·세션은 유지. 이 매장 활성 소속이 아니면(다른 매장·이미 나감) 403. 재참여는 join으로 **새 membership 행**
- 탈퇴: 본인 소속 전부(ACTIVE·LEFT) id 오름차순 FOR UPDATE → (활성 소속이 있으면) 사전 검사 + cleanupOnMembershipLeave
  → 소속마다 `Lifecycle.anonymizeFor(id)` 후 state = WITHDRAWN, user_id = NULL(LEFT였으면 left_at 유지)
  → idempotency_records(B의 deleteByUserId 호출)·auth_sessions·social_accounts·user_consents 삭제
  → users: display_name·selected_role NULL, WITHDRAWN, withdrawn_at (행은 개인 연결 없는 톰스톤으로 남긴다)
- 탈퇴 직후 기존 토큰은 401(세션 없음 + 계정 WITHDRAWN). 같은 소셜 계정으로 다시 로그인하면 새 회원(CONSENT_REQUIRED)
- B 처리(cleanup·anonymize)에서 예외가 나면 전체 롤백
- 잠금 순서: user → membership → shift → substitute_request (매장 생성·참여와 같음). B의 lockActiveById·일괄 등록 FOR SHARE와
  같은 membership 행에서 직렬화된다

B에게 확인 요청
- `SubstituteRequestLifecycleService.cleanupOnMembershipLeave`·`anonymizeFor`가 아직 빈 구현이다.
  정책 D05의 "본인 수신 알림 파기"는 anonymizeFor 책임이므로 구현이 필요하다(A는 호출만 한다)
- anonymizeFor는 A가 user_id를 끊기 **전에** 호출된다(호출 시점에 membership 행은 잠겨 있고 아직 user_id가 있다)
