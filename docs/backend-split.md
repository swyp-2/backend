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
- 응답: {"data": ..., "meta": {"server_time"}} / 에러: {"error": {"code","message","field_errors","retryable"}, "current_state"?}
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
    }
    public record ShiftSnapshot(long shiftId, long storeId, long assigneeMembershipId,
                                Instant startsAt, Instant endsAt, long version, boolean deleted) {}

    public interface MembershipQuery {
        // 활성 소속이 아니면 BusinessException(FORBIDDEN)
        ActiveMembership requireActive(long userId, long storeId);
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
