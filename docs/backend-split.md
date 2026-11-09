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