# 06 · API 계약 초안

# BATON API 계약 초안

기준일  · v3.0

프론트·백엔드 합의용으로 경로·필드·오류 후보와 예시를 채웠다. 제품 동작은 기능명세서 v3.2·정책서 v2.0이 기준이다. 아래 경로·응답 형식을 실제 구현·배포 완료 계약으로 간주하지 않는다.

## 1. 공통 계약 초안

| 항목 | 계약 기준 |
| --- | --- |
| 인증·권한 | 인증 사용자·현재 소속·역할·요청 관계를 서버에서 판정. actor\_id/role을 요청 본문에서 받아 권한으로 신뢰하지 않음. |
| 일시 | 전송 일시는 UTC ISO 8601을 제안. 월/선택일은 Asia/Seoul 기준으로 해석. 익일 종료 보존. |
| ID·버전 | ID는 불투명 식별자. 변경은 expected\_version 등 최신성 검사·서버 현재 상태 재확인. 필드명은 개발팀 최종 확정. |
| 목록 | 요청·알림은 생성 시각 DESC, ID DESC. 기본 20개·커서 추가 조회. 상태 변경으로 요청 순서 재정렬 안 함. |
| 오류 | 인증 만료·권한 없음·입력 오류·없는 데이터·상태 충돌·조회/저장 실패를 구분. HTTP 매핑은 아래 제안. |
| 개인정보 | 일반 구성원 응답에 이메일·소셜 식별자 미포함. 파기 대상 스냅샷의 이름을 예외 보관하지 않음. |
| 제외 | 근무 수정 PATCH·변경 이력 조회·알림 pending 필터·코드 재발급·공유 링크·푸시 API는 이번 초안에 없음. |

## 2. 엔드포인트와 책임

| 메서드 | 경로 후보 | 주체 | 화면 | 책임 | 검증 |
| --- | --- | --- | --- | --- | --- |
| POST | /auth/{provider}/complete | 공통 | CO-01 | Google/Apple 인증 완료·회원 세션 연결 | SDK 자격증명/코드 형태·세션 수명은 계약 확정. 실패·취소를 성공 처리하지 않음. |
| GET | /me | 본인 | CO-01\~05 / PR-01 | 회원·가입 단계·선택 역할·현재 소속 조회 | 소속 없는 계정을 허용하고 다음 가입 단계로 분기. 타인 정보 없음. |
| GET | /consent-documents | 가입 사용자 | CO-02/03 | 동의할 문서·버전·본문 조회 | 문서 종류·본문 후속. 보기 자체는 동의 저장 아님. |
| POST | /me/consents | 본인 | CO-02 | 문서 버전별 동의 기록 저장 | 필수 미동의 차단·선택 미동의 허용·서버 시각 기록. |
| PUT | /me/onboarding/name | 본인 | CO-04 | 가입 이름 저장 | 공백 제거 후 2\~15자. 이름 입력만으로 매장 소속 생성 안 함. |
| PUT | /me/onboarding/role | 본인 | CO-05 | 가입 역할 저장 | MANAGER/CREW만. 기존 매장 권한 승격 API 아님. |
| POST | /stores | 매니저 역할 가입자 | ST-01 | 매장·생성자 매니저 소속 생성 | 매장명 1\~15자, 재시도 중복 방지. |
| GET | /stores/{store\_id} | 현재 소속 | PR-02 | 소속 매장 기본 정보 | 다른 매장 접근 차단. |
| GET | /stores/{store\_id}/invite-code | 소속 매니저 | ST-02 / PR-02 | 매장 공통 코드 확인 | 대문자·숫자 8자리. 재발급 API 없음. |
| POST | /memberships/join | 크루원 역할 가입자 | ST-03 | 유효 코드 즉시 참여 | 참여 승인 단계 없음. 잘못된 코드·중복 소속 구분. |
| GET | /stores/{store\_id}/members | 현재 소속 | ED-01 / RQ-01 / PR-02 | 풀네임·역할·색상·소속 상태 조회 | 일반 근무 배정에는 매니저 포함 가능. 대타 후보는 본인·매니저 제외 필터. |
| GET | /stores/{store\_id}/shifts | 현재 소속 | CA-01/02 | 월 근무·날짜 인원 요약 조회 | scope=mine/all. 내 근무 월 범위를 날짜 선택으로 줄이지 않음. |
| GET | /stores/{store\_id}/shifts?date=... | 현재 소속 | CA-03 | 선택일 전체 명단 조회 | 전원·시작시각/이름/소속ID 순. 인원과 건수 분리. |
| GET | /shifts/{shift\_id} | 현재 소속 | SH-01/02 | 근무 상세·관련 요청·가능 행동 조회 | 본인 미래 근무 조건·매니저 승인 대체 진입·삭제 권한 계산. |
| POST | /shift-batches/preview | 소속 매니저 | ED-01\~03 | 근무 생성 계획·중복 제외·미리보기 | 입력 검증·달력상 1개월·요일. 근무 저장 없음. |
| POST | /shift-batches | 소속 매니저 | ED-03 | 미리보기 확인 후 최종 일괄 등록 | 서버 재검증·전부 성공/실패·중복 제출 결과 재현. 단건도 같은 계약 사용 가능. |
| DELETE | /shifts/{shift\_id} | 소속 매니저 | SH-02 | 근무 단건 삭제·활성 요청 종료 | 최신 버전·삭제 확인 후 호출. 다른 날짜 전파 없음. |
| POST | /substitute-requests | 크루원 | RQ-01 | 대타 요청·대상·새 요청 알림 생성 | 본인 시작 전·후보 크루원 1명 이상·같은 근무 활성 한 건. |
| GET | /me/substitute-requests | 크루원 | RQ-02 | 보낸/받은 요청 목록 | box=sent/received, 생성시각·ID DESC, 20개 커서 조회. |
| GET | /substitute-requests/{request\_id} | 요청 관계자·소속 매니저 | RQ-03 / AP-02 | 요청 최신 상태·원본 정보·후보·기록·가능 행동 | 다른 매장·비대상 차단. 이름 대신 ID·관계 검사. |
| POST | /substitute-requests/{request\_id}/accept | 지정 크루원 | RQ-03 | 첫 수락 후보 확정 | WAITING·시작 전. 거절 대상의 유효 재수락 포함. 동시 처리·중복 실행 방지. |
| POST | /substitute-requests/{request\_id}/reject | 미응답 지정 크루원 | RQ-03 | 본인 거절 응답 | 확인 팝업 없이 즉시 호출. 전원 거절만 전체 종료. 동시 처리·중복 실행 방지. |
| POST | /substitute-requests/{request\_id}/cancel | 요청 크루원 | RQ-03 | 요청 취소 | 확인 뒤 WAITING/PENDING\_MANAGER에서만. 동시 처리·중복 실행 방지. |
| POST | /substitute-requests/{request\_id}/withdraw | 현재 후보 크루원 | RQ-03 | 수락 철회 | 확인 뒤 PENDING\_MANAGER에서만. 전체 종료·자동 재모집 없음. 동시 처리·중복 실행 방지. |
| POST | /substitute-requests/{request\_id}/approve | 소속 매니저 | AP-02 | 대타 최종 승인 | 확인 뒤 PENDING\_MANAGER·시작 전·원본/후보 유효. 동시 처리·중복 실행 방지. |
| POST | /substitute-requests/{request\_id}/final-reject | 소속 매니저 | AP-02 | 대타 최종 거절 | 확인 뒤 PENDING\_MANAGER. 사유 입력 필드 없음. 동시 처리·중복 실행 방지. |
| POST | /me/notifications/open | 본인 | NT-01 | 진입 스냅샷까지 기존 알림 읽음·첫 목록 반환 | 목록/읽음 진입을 한 계약으로 처리. 다음에 도착한 알림은 미읽음. |
| GET | /me/notifications | 본인 | NT-01 | 추가 알림 페이지 조회 | 생성시각·ID DESC·20개·7일 유효·묶음 없음. 추가 조회 자체는 새 알림 읽음 아님. |
| GET | /me/notification-indicator | 본인 | CA-01/02 | 최근 7일 미읽음 존재 여부 | 매니저도 승인 대기 건수가 아닌 미읽음 기준. |
| POST | /auth/logout | 본인 | PR-04 | 현재 세션 종료 | 계정·매장 소속은 삭제하지 않음. |
| POST | /stores/{store\_id}/leave | 본인 소속 | PR-04 | 확인 후 매장 나가기 | 미종료 배정 근무 차단·진행 요청 정리·접근 해제 동시 확정. 마지막 매니저 특수 처리 후속. |
| DELETE | /me | 본인 | PR-04 | 확인 후 계정 탈퇴 | 미종료 배정 근무 검사·요청 정리·일반 개인정보 파기·법정 보안기록 구분. |

## 3. 공통 응답·오류 예시

예시 ID·시각은 설명용이다. 실제 토큰·계정 식별자나 사용자 개인정보를 문서에 넣지 않는다.

```json
{
  "data": {
    "id": "request_example",
    "status": "PENDING_MANAGER",
    "version": 2,
    "allowed_actions": [
      "cancel"
    ]
  },
  "meta": {
    "server_time": "2026-11-04T12:00:00Z"
  }
}
```

```json
{
  "error": {
    "code": "REQUEST_STATE_CONFLICT",
    "message": "이미 다른 동료가 수락했어요. 최신 요청을 확인해 주세요.",
    "field_errors": [],
    "retryable": false
  },
  "current_state": "PENDING_MANAGER"
}
```

| 오류 코드 후보 | HTTP 제안 | 처리 |
| --- | --- | --- |
| UNAUTHENTICATED | 401 | 로그인·세션 복구. |
| FORBIDDEN | 403 | 비대상·다른 역할·소속 없음. |
| NOT\_FOUND | 404 | 없어진 근무·요청. 필요에 따라 비공개 자원 노출도 막음. |
| VALIDATION\_FAILED | 422 | 이름·매장명·코드·시간·요일 오류를 필드와 함께 반환. |
| INVALID\_INVITE\_CODE | 422 | 유효한 매장 코드를 다시 입력하도록 안내. |
| ACTIVE\_REQUEST\_EXISTS | 409 | 새로 만들지 않고 접근 가능한 기존 요청으로 연결. |
| REQUEST\_STATE\_CONFLICT | 409 | 다른 수락·승인·취소·철회와 경합. 최신 상태 재조회. |
| REQUEST\_EXPIRED | 409 | 시작 시각 이후 처리 불가. |
| PREVIEW\_STALE | 409 | 입력 유지·미리보기 재계산 요청. |
| FUTURE\_SHIFT\_REMAINS | 409 | 예정/진행 근무 정리 안내. 소속 해제·탈퇴 미확정. |
| INTERNAL\_ERROR | 5xx | 완료 표시 금지. 중복 방지 키로 재시도 가능 여부 확인. |

## 4. 일괄 등록 예시

```json
{
  "store_id": "store_example",
  "assignee_membership_ids": [
    "crew_a",
    "crew_b"
  ],
  "starts_at": "2026-11-11T00:00:00Z",
  "ends_at": "2026-11-11T04:00:00Z",
  "repeat": {
    "enabled": true,
    "start_date": "2026-11-11",
    "end_date": "2026-12-11",
    "weekdays": [
      1,
      3,
      5
    ]
  }
}
```

위 입력의 종료일은 달력상 최대 1개월 범위 안에 있다. 양 끝을 포함한 월·수·금은 14일이고 2명에게 적용하면 총 28건이다. 아래는 그중 완전 동일 중복이 2건 이미 존재한다고 가정한 요약 응답이다.

```json
{
  "data": {
    "preview_token": "opaque_preview",
    "create_count": 26,
    "duplicate_count": 2,
    "total_count": 28
  }
}
```

실제 생성 계획과 중복 수는 서버에서 계산한다. 프론트가 보낸 총계나 미리보기 토큰만 신뢰하지 않고 최종 등록에서 권한·중복·시간·구성원을 다시 검증한다. 최종 요청에는 preview\_token과 idempotency\_key 등 합의한 중복 방지 정보를 사용한다.

## 5. 요청 상세와 가능 행동 예시

```json
{
  "data": {
    "id": "request_example",
    "shift_id": "shift_example",
    "status": "PENDING_MANAGER",
    "version": 2,
    "requester": {
      "membership_id": "crew_a",
      "display_name": "김도연"
    },
    "candidate": {
      "membership_id": "crew_b",
      "display_name": "박소율"
    },
    "source_deleted": false,
    "allowed_actions": [
      "approve",
      "final_reject"
    ],
    "events": [
      {
        "kind": "REQUEST_CREATED"
      },
      {
        "kind": "CANDIDATE_ACCEPTED"
      }
    ]
  }
}
```

이 응답은 소속 매니저 예시다. 같은 요청을 요청자가 조회하면 cancel, 현재 후보는 withdraw, 후보 아닌 지정 대상은 처리 행동 없음으로 달라진다. 가능한 행동을 응답하더라도 실제 변경 시 다시 검사한다. 거절 사유 문자열 필드는 두지 않는다.

## 6. 알림 화면 진입 계약

1. NT-01 진입 시 POST /me/notifications/open을 한 번 호출한다. visit\_key 등으로 같은 진입의 재시도를 구분하는 방식을 협의한다.
2. 서버가 현재 수신자의 최근 7일 유효 알림과 진입 스냅샷/워터마크를 확정하고 그 시점의 기존 알림 전체를 읽음 처리한다. 첫 20개만 읽는 구현은 하지 않는다.
3. 같은 응답으로 첫 목록·읽음 결과·추가 커서를 돌려준다. 이후 도착한 알림은 미읽음으로 유지한다. 동일 시각 알림도 워터마크로 선후를 구분한다.
4. 추가 페이지는 GET으로 읽기만 한다. 새 알림을 자동 읽음 처리하지 않는다. 카드 선택은 상세 조회이지 승인/수락이 아니다.
5. 생성 후 정확히 7일인 알림은 응답·표시점에서 제외한다. 요청/승인 상태는 보관 만료로 삭제하지 않는다.

```json
{
  "data": {
    "visit_id": "visit_example",
    "read_through_token": "opaque_snapshot",
    "items": [
      {
        "id": "notification_example",
        "event_kind": "CANDIDATE_ACCEPTED",
        "request_id": "request_example",
        "current_request_status": "PENDING_MANAGER",
        "read_at": "2026-11-04T12:00:00Z",
        "created_at": "2026-11-04T11:00:00Z",
        "expires_at": "2026-11-11T11:00:00Z",
        "destination": {
          "screen_id": "AP-02",
          "request_id": "request_example"
        }
      }
    ],
    "next_cursor": null,
    "has_more": false,
    "has_unread": false
  }
}
```

read\_through\_token과 커서는 클라이언트가 만들어 보낸 시각을 믿기 위한 값이 아니다. 서버가 유효 범위·수신자·스냅샷을 검증하는 불투명 값으로 설계한다. 단일 목록이므로 filter=pending·AP-01 계약은 제공하지 않는다.

## 7. 상태 변경·갱신 책임

| 변경 | 프론트 후속 | 서버 보장 |
| --- | --- | --- |
| 근무 등록·삭제 | 근무표 월 요약·선택일·내 근무 재조회 | 한 번만 저장·삭제와 요청 종료 일관성. |
| 요청 생성·응답 | RQ-02/03·관련 근무 상세·알림 재조회 | 최신 관계·후보·상태. |
| 승인 | 담당자·근무표·요청 결과 갱신 | 한 근무의 담당자 변경과 APPROVED/기록/알림 확정. |
| 알림 진입 | 표시점·목록 상태 갱신 | 기존 읽음과 후발 미읽음 구분. |
| 나가기·탈퇴 | 캐시 제거·이전 매장 화면 접근 종료 | 소속·관련 요청 정리와 접근 권한 해제. |

실시간 전송 기술은 미정이다. 최소한 변경 성공·상세 재진입·목록 재진입에서 최신 데이터를 조회하는 흐름으로 합의할 수 있으며, 특정 WebSocket·폴링 주기를 정책처럼 확정하지 않는다.

## 8. API 합의 기록

| 항목 | 확인할 내용 | 담당자 | 결정값 |
| --- | --- | --- | --- |
| 인증 | 제공자 자격증명·세션/토큰·만료·로그아웃 범위 |  |  |
| 실제 경로·스키마 | 이 문서 경로·필드·HTTP 코드·allowed\_actions 확정 |  |  |
| 일괄 저장·멱등성 | 키·미리보기 만료·전체 성공/실패·재시도 결과 |  |  |
| 커서·알림 진입 | 동일 시각 선후·스냅샷·읽음 재시도 |  |  |
| 야간 월경계 | 조회 예시의 시작일 기준 노출을 팀과 확인 |  |  |
| 갱신·배포 | 동기화 방식·서버 URL·Swagger 위치·사용 가능한 API별 일정 |  |  |

API 명세 제공일과 구현 완료·프론트 연동 완료는 서로 다른 상태로 보고한다. 문서에 예시가 있다는 이유로 운영 API 사용 가능으로 표시하지 않는다.

## 관련 문서

[02 · 기능명세서](https://swyp7.atlassian.net/wiki/spaces/72/pages/7307311/02) · [03 · 서비스 정책](https://swyp7.atlassian.net/wiki/spaces/72/pages/7307266/03) · [05 · 데이터·ERD 설계 초안](https://swyp7.atlassian.net/wiki/spaces/72/pages/6881301/05+ERD) · [07 · QA·인수 기준](https://swyp7.atlassian.net/wiki/spaces/72/pages/6881318/07+QA)
