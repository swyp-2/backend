# 로그인 연동 가이드 (프론트용)

> 예시의 토큰·ID·시각은 모두 가짜 값이다. 실제 토큰을 로그나 문서에 남기지 말 것.

## 1. 전체 흐름

```
[Google/Apple SDK 로그인] → id_token 획득
        │
        ▼
POST /auth/{google|apple}/complete  ──►  access_token(30분) + refresh_token(14일) + onboarding_stage
        │
        ▼
이후 모든 API: Authorization: Bearer <access_token>
        │  401 UNAUTHENTICATED
        ▼
POST /auth/refresh (1회)  ── 성공 ──► 새 토큰 쌍 저장 → 원래 요청 재시도
        │ 실패(401)
        ▼
토큰 삭제 → 로그인 화면(CO-01)
```

## 2. 공통 형식

- JSON 필드는 snake_case, 시각은 UTC ISO 8601.
- 성공: `{"data": {...}, "meta": {"server_time": "..."}}`
- 실패: `{"error": {"code", "message", "field_errors", "retryable"}}`
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
    "refresh_token": "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c",
    "expires_in": 1800,
    "onboarding_stage": "CONSENT_REQUIRED"
  },
  "meta": { "server_time": "2026-11-07T01:00:00Z" }
}
```

- 처음 보는 소셜 계정이면 자동으로 가입이 시작된다(`CONSENT_REQUIRED`).
- 같은 계정으로 버튼을 두 번 눌러도 회원은 하나만 생긴다.
- 탈퇴한 계정과 같은 소셜 계정으로 다시 로그인하면 **새 회원**으로 처리된다.

### onboarding_stage별 이동 화면

| onboarding_stage | 이동 |
|---|---|
| `CONSENT_REQUIRED` | CO-02 약관 동의 |
| `NAME_REQUIRED` | CO-04 이름 입력 |
| `ROLE_REQUIRED` | CO-05 역할 선택 |
| `STORE_REQUIRED` | 매장 만들기/참여 (역할에 따라 ST-01·ST-03) |
| `COMPLETED` | 근무표. 소속 매장은 `GET /me`로 확인 (나간 경우 소속이 없을 수 있음) |

### 오류

| 상황 | HTTP | code |
|---|---|---|
| id_token 누락·공백 | 422 | `VALIDATION_FAILED` (`field_errors[0].field = "id_token"`) |
| 서명·발급자·대상(aud)·만료 검증 실패 | 401 | `UNAUTHENTICATED` |
| 지원하지 않는 provider 경로 | 401 | `UNAUTHENTICATED` |
| 서버가 Google/Apple 키를 못 가져옴 | 500 | `INTERNAL_ERROR` (`retryable: true`, 잠시 후 재시도) |

사용자가 소셜 로그인을 **취소**하면 서버를 호출하지 말고 로그인 화면에 머문다. 실패를 성공처럼 표시하지 않는다.

## 4. 토큰 갱신 — `POST /auth/refresh`

인증 헤더 없이 호출한다.

```json
{ "refresh_token": "q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c" }
```

응답은 complete와 같은 형식이며 **refresh_token도 새 값으로 바뀐다(회전)**. 응답을 받으면 두 토큰을 모두 교체 저장한다.
쓴 refresh_token은 즉시 무효가 된다.

주의 — 재사용 탐지

- 이미 쓰였거나 로그아웃된 refresh_token이 다시 오면 탈취로 보고 **그 계정의 모든 기기 세션을 끊는다**.
- 그래서 refresh는 앱 전체에서 **동시에 하나만** 진행해야 한다. 여러 요청이 동시에 401을 받으면
  첫 요청만 refresh하고 나머지는 그 결과를 기다렸다가 새 access_token으로 재시도한다(single-flight).
- refresh 응답이 네트워크 오류로 유실되면 그 refresh_token은 이미 회전됐을 수 있다. 같은 값으로 재시도하면
  401이 나고 전 기기가 로그아웃되므로, 이 경우는 로그인 화면으로 보낸다.

401이면 저장된 토큰을 지우고 로그인 화면으로 이동한다.

## 5. 인증이 필요한 요청

```
Authorization: Bearer <access_token>
```

- access_token 수명은 30분(`expires_in` 초). 만료 직전에 미리 갱신하거나, 401을 받으면 4번 흐름으로 갱신한다.
- 로그아웃·탈퇴·재사용 탐지로 세션이 끊기면 만료 전이라도 바로 401이 온다.
- 401 응답 본문: `{"error":{"code":"UNAUTHENTICATED","message":"로그인이 필요해요. 다시 로그인해 주세요.","field_errors":[],"retryable":false}}`
- 403 `FORBIDDEN`은 로그인은 됐지만 그 매장·작업 권한이 없는 경우다. refresh로 해결되지 않는다.

## 6. 로그아웃 — `POST /auth/logout`

```
POST /auth/logout
Authorization: Bearer <access_token>
```

- 응답 204, 본문 없음. 현재 기기 세션만 끝나고 계정·매장 소속·다른 기기 로그인은 유지된다.
- 응답 후 저장한 access/refresh 토큰을 **반드시 삭제**한다(로그아웃한 refresh_token을 다시 쓰면 4번의 재사용 탐지로
  모든 기기가 로그아웃된다). 이후 CO-01 로그인 화면으로 이동.

## 7. 토큰 보관

- 모바일: refresh_token은 iOS Keychain / Android EncryptedSharedPreferences(Keystore)에 저장.
- 웹: localStorage 저장을 피하고 메모리 보관을 권장(새로고침 시 재로그인 또는 별도 합의).
- 토큰 원문을 로그·분석 도구·크래시 리포트에 보내지 않는다.

## 8. 로컬 개발 (local 프로필 서버 전용)

`X-Test-User-Id: <user_id>` 헤더로 소셜 로그인 없이 해당 사용자로 인증할 수 있다.
운영(prod) 서버에는 이 기능이 존재하지 않는다.
