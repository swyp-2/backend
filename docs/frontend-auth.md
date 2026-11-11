# 로그인 연동 가이드 (프론트용)

> 예시의 토큰·ID·시각은 모두 가짜 값이다. 실제 토큰을 로그나 문서에 남기지 말 것.

## 0. 서버 정보

| 항목 | 값 |
|---|---|
| 개발 서버 Base URL | `http://3.36.158.96` (임시. HTTPS 도메인 연결 후 `https://<도메인>`으로 바뀜) |
| API 문서 (Swagger UI) | `<Base URL>/swagger-ui.html` |
| OpenAPI JSON | `<Base URL>/v3/api-docs` |
| 상태 확인 | `GET <Base URL>/health` |

- iOS는 ATS 때문에 http 호출이 막히므로, iOS 실기기 연동은 HTTPS 도메인 연결 이후에 진행한다.
  (그 전에 테스트가 필요하면 개발 빌드에서만 ATS 예외를 둔다.)
- 경로에 `/api` 같은 prefix는 없다. 예: `POST <Base URL>/auth/google/complete`

### 백엔드에 알려줘야 하는 값

서버는 아래 값과 토큰의 `aud`/요청 Origin이 일치할 때만 허용한다. 정해지는 대로 백엔드에 전달할 것.

| 값 | 용도 |
|---|---|
| Google OAuth 클라이언트 ID (iOS / Android / Web 각각) | Google id_token의 `aud` 검증 |
| iOS 앱 번들 ID | Apple identityToken의 `aud` 검증 |
| Apple Services ID (웹에서 Apple 로그인을 쓸 때만) | 웹 Apple 로그인 `aud` 검증 |
| 웹 프론트 Origin (예: `https://app.example.com`, `http://localhost:5173`) | CORS 허용 목록. 모바일 앱은 필요 없음 |

## 1. 전체 흐름

```
[Google/Apple SDK 로그인] → id_token 획득
        │
        ▼
POST /auth/{google|apple}/complete  ──►  access_token(14일) + onboarding_stage
        │
        ▼
이후 모든 API: Authorization: Bearer <access_token>
        │  401 UNAUTHENTICATED (만료·로그아웃·탈퇴)
        ▼
토큰 삭제 → 로그인 화면(CO-01)
```

토큰 갱신(refresh) API는 없다. 토큰이 만료되면 다시 소셜 로그인한다.

## 2. 공통 형식

- JSON 필드는 snake_case, 시각은 UTC ISO 8601.
- 성공: `{"data": {...}, "meta": {"server_time": "..."}}`. 본문이 없는 성공은 **204 No Content**.
- 실패:

```json
{
  "error": {
    "code": "REQUEST_STATE_CONFLICT",
    "message": "요청 상태가 바뀌었어요. 최신 요청을 확인해 주세요.",
    "field_errors": [{ "field": "target_membership_ids", "reason": "..." }],
    "retryable": false
  },
  "current_state": "PENDING_MANAGER",
  "existing_request_id": 12
}
```

  - 분기는 `error.code`로 한다. `message`는 그대로 사용자에게 보여줘도 되는 문구다.
  - `current_state`(409 상태 충돌 시 최신 상태), `existing_request_id`(409 `ACTIVE_REQUEST_EXISTS`일 때만)는
    **`error` 바깥 최상위**에 있고, 해당할 때만 나온다.
  - `retryable: true`면 잠시 후 같은 요청을 재시도해도 된다(현재 500 `INTERNAL_ERROR`만 해당).
- HTTP 상태: 401 로그인 필요 / 403 권한 없음 / 404 없음 / 409 상태 충돌 / 422 입력 오류 / 500 서버 오류.
  입력 오류는 400이 아니라 **422**다.
- 시각 필드(`*_at`)는 UTC다. 화면에는 Asia/Seoul로 변환해 표시한다. 날짜만 있는 필드(`local_date`, `month`)는 이미 서울 기준이다.
- 응답에 이메일·소셜 식별자는 포함되지 않는다. 서버도 이메일을 저장하지 않는다.

## 3. 로그인 완료 — `POST /auth/{provider}/complete`

`provider`는 `google` 또는 `apple`. 인증 헤더 없이 호출한다(만료된 헤더가 붙어 있어도 무시됨).

요청

```json
{ "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6ImV4YW1wbGUifQ.example.signature" }
```

- Google: SDK가 준 **ID 토큰**(access token 아님). aud는 iOS/Android/Web 클라이언트 ID 중 하나여야 한다.
- Apple: `identityToken`. aud는 앱 번들 ID(웹은 Services ID)여야 한다.

응답 200

```json
{
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiJ9.example.signature",
    "expires_in": 1209600,
    "onboarding_stage": "CONSENT_REQUIRED"
  },
  "meta": { "server_time": "2026-11-07T01:00:00Z" }
}
```

- 처음 보는 소셜 계정이면 자동으로 가입이 시작된다(`CONSENT_REQUIRED`).
- 같은 계정으로 버튼을 두 번 눌러도 회원은 하나만 생긴다.
- 탈퇴한 계정과 같은 소셜 계정으로 다시 로그인하면 **새 회원**으로 처리된다.
- 로그인할 때마다 새 세션(새 토큰)이 생긴다. 다른 기기의 로그인은 그대로 유지된다.

### 로그인 후 이동 화면 — `GET /me`의 `next_screen`을 따른다

화면설계서 CO-01("인증 성공 후 서버의 가입 상태·소속을 조회")과 API 계약 초안의 409 처리("GET /me 재조회 후
next_screen으로 이동")를 따른다. 로그인 응답의 `onboarding_stage`만으로는 ST-01(매장 만들기)과 ST-03(코드 참여) 중
어디로 갈지 알 수 없으므로, 아래 시점에 `GET /me`를 호출해 `next_screen`으로 이동한다.

- 로그인 직후
- 앱 시작 시(저장된 토큰이 있을 때)
- 409 `ONBOARDING_STAGE_CONFLICT`를 받았을 때
- 매장 나가기 성공 후

**`next_screen`을 쓰지 않는 경우**: 가입 흐름 안의 화면 이동은 화면설계서대로 프론트가 직접 한다.

- `POST /stores` 성공 → **ST-02**(크루 초대) → "근무표 시작하기" → CA-02
  (이때 `GET /me`를 부르면 소속이 생겨 `CA-01`이 오므로, 따르면 ST-02를 건너뛰게 된다)
- `POST /memberships/join` 성공 → CA-01

| next_screen | 화면 | 조건 |
|---|---|---|
| `CO-02` | 약관 동의 | 동의 전 |
| `CO-04` | 이름 입력 | 이름 입력 전 |
| `CO-05` | 역할 선택 | 역할 선택 전 |
| `ST-01` | 매장 만들기 | 매니저 역할, 활성 소속 없음 |
| `ST-03` | 초대 코드로 참여 | 크루원 역할, 활성 소속 없음 |
| `CA-01` | 근무표 | 활성 소속 있음 (`membership`에 매장 정보) |

> **합의 필요**: 활성 소속이 있으면 역할과 관계없이 `CA-01`(내 근무)이 온다. 화면설계서는 "근무표"로만 적혀 있고,
> 매니저 첫 진입은 CA-02(전체 근무)로 끝난다. 재진입한 매니저의 첫 탭을 CA-01로 둘지 CA-02로 바꿀지 정해야 한다.

- 매장을 나가면 가입 단계가 `STORE_REQUIRED`로 돌아가고, 동의·이름·역할은 다시 묻지 않는다(`next_screen`이 ST-01/ST-03).
- 가입 단계 저장 API(`/me/consents`, `/me/onboarding/name`, `/me/onboarding/role`)도 응답으로 최신 `/me` 내용을 주므로
  그 응답의 `next_screen`으로 이동하면 된다.
- `membership.role`이 실제 매장 권한이다. `selected_role`(가입 때 고른 역할)로 화면 권한을 판단하지 않는다.

### 오류

| 상황 | HTTP | code |
|---|---|---|
| id_token 누락·공백 | 422 | `VALIDATION_FAILED` (`field_errors[0].field = "id_token"`) |
| 서명·발급자·대상(aud)·만료 검증 실패 | 401 | `UNAUTHENTICATED` |
| 지원하지 않는 provider 경로 | 404 | `NOT_FOUND` (Authorization 헤더 유무와 관계없음) |
| 서버가 Google/Apple 키를 못 가져옴 | 500 | `INTERNAL_ERROR` (`retryable: true`, 잠시 후 재시도) |

사용자가 소셜 로그인을 **취소**하면 서버를 호출하지 말고 로그인 화면에 머문다. 실패를 성공처럼 표시하지 않는다.

## 4. 토큰 수명

- access_token 수명은 **발급 시각부터 14일**(`expires_in` = 1209600초)이다.
- **사용해도 연장되지 않는다.** 매일 앱을 써도 로그인 후 14일이 지나면 만료된다.
- 만료되면 401 `UNAUTHENTICATED`가 오고, 저장된 토큰을 지운 뒤 로그인 화면(CO-01)으로 이동한다(기능명세 COM-01).
- 만료 전이라도 로그아웃·탈퇴하면 그 세션은 즉시 401이 된다.

## 5. 인증이 필요한 요청

```
Authorization: Bearer <access_token>
```

- 401 응답 본문: `{"error":{"code":"UNAUTHENTICATED","message":"로그인이 필요해요. 다시 로그인해 주세요.","field_errors":[],"retryable":false}}`
- 401이면 재시도하지 말고 토큰을 지운 뒤 로그인 화면으로 이동한다.
- 403 `FORBIDDEN`은 로그인은 됐지만 그 매장·작업 권한이 없는 경우다. 다시 로그인해도 해결되지 않는다.

## 6. 로그아웃 — `POST /auth/logout`

```
POST /auth/logout
Authorization: Bearer <access_token>
```

- 응답 204, 본문 없음(본문 없는 성공은 모든 API에서 204로 통일). 현재 기기 세션만 끝나고 계정·매장 소속·다른 기기 로그인은 유지된다.
- 응답 후 저장한 access_token을 삭제하고 CO-01 로그인 화면으로 이동한다.

## 6-1. 약관 동의 (CO-02 / CO-03)

- `GET /consent-documents`의 `body_reference`는 **약관 본문 URL**(https 절대 경로)이다. CO-03에서 이 URL의 본문을 보여준다.
  보기·닫기만으로는 동의가 저장되지 않는다.
- `POST /me/consents`의 `agreements`에 빠진 선택 약관은 서버가 **"동의 안 함"으로 기록**한다.
  필수 약관이 빠지거나 `agreed: false`면 422 `VALIDATION_FAILED`(`field`: `agreements[document_id=N]`).
- 이미 지난 가입 단계를 다시 저장하면 409 `ONBOARDING_STAGE_CONFLICT`(`current_state`에 현재 단계).
  `GET /me`로 다시 조회해 `next_screen`으로 이동한다.

## 6-2. 매장 나가기·탈퇴 후 처리

| API | 성공 | 이후 처리 |
|---|---|---|
| `POST /stores/{store_id}/leave` | 204 | 토큰은 유지된다. `GET /me` → `next_screen`(ST-01/ST-03)으로 이동. 이전 매장 API는 403 |
| `DELETE /me` | 204 | 모든 기기의 세션이 끝난다. 저장된 토큰을 지우고 CO-01로 이동 |

- 둘 다 끝나지 않은 본인 근무(예정·진행 중)가 있으면 409 `FUTURE_SHIFT_REMAINS`이고 아무것도 바뀌지 않는다.

## 6-3. 중복 제출 방지 — `Idempotency-Key` 헤더

아래 **생성·상태 변경 API는 `Idempotency-Key` 헤더가 필수**다. 없거나 형식이 틀리면 422 `VALIDATION_FAILED`
(`field_errors[0].field = "Idempotency-Key"`). (Swagger에 일부가 "required: false"로 보이지만 실제로는 필수다.)

- `POST /stores`, `POST /shift-batches`
- `POST /substitute-requests`, `POST /substitute-requests/{id}/{accept|reject|cancel|withdraw|approve|final-reject}`

규칙

- 값: 영문·숫자·`-`·`_` 1~100자. **UUID v4 권장**.
- 사용자의 행동(버튼 탭) 1회마다 새 키를 만든다. 네트워크 오류·타임아웃으로 **같은 요청을 재시도할 때는 같은 키**를 다시 보낸다.
  → 서버는 처음 결과를 그대로 돌려주고 중복 생성하지 않는다.
- 같은 키를 다른 본문으로 다시 쓰면 422 `IDEMPOTENCY_KEY_REUSED`.
- 그 밖의 API(로그인, 온보딩 저장, 코드 참여, 미리보기, 근무 삭제 등)는 헤더가 필요 없다.

## 7. 토큰 보관

- 모바일: access_token은 iOS Keychain / Android EncryptedSharedPreferences(Keystore)에 저장.
- 웹: localStorage 저장을 피하고 메모리 보관을 권장(새로고침 시 재로그인 또는 별도 합의).
- 토큰 원문을 로그·분석 도구·크래시 리포트에 보내지 않는다.

## 8. 로컬 개발 (local 프로필 서버 전용)

`X-Test-User-Id: <user_id>` 헤더로 소셜 로그인 없이 해당 사용자로 인증할 수 있다.
운영(prod) 서버에는 이 기능이 존재하지 않는다.
