# 로그인 연동 가이드 (프론트용)

> 예시의 토큰·ID·시각은 모두 가짜 값이다. 실제 토큰을 로그나 문서에 남기지 말 것.

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

- 응답 204, 본문 없음. 현재 기기 세션만 끝나고 계정·매장 소속·다른 기기 로그인은 유지된다.
- 응답 후 저장한 access_token을 삭제하고 CO-01 로그인 화면으로 이동한다.

## 7. 토큰 보관

- 모바일: access_token은 iOS Keychain / Android EncryptedSharedPreferences(Keystore)에 저장.
- 웹: localStorage 저장을 피하고 메모리 보관을 권장(새로고침 시 재로그인 또는 별도 합의).
- 토큰 원문을 로그·분석 도구·크래시 리포트에 보내지 않는다.

## 8. 로컬 개발 (local 프로필 서버 전용)

`X-Test-User-Id: <user_id>` 헤더로 소셜 로그인 없이 해당 사용자로 인증할 수 있다.
운영(prod) 서버에는 이 기능이 존재하지 않는다.
