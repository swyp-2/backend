# BATON Backend

매장 근무표 + 크루원 대타 요청 + 매니저 최종 승인 MVP의 백엔드.

## 기술 스택
Java 21, Spring Boot 4.x(Jackson 3: tools.jackson, Testcontainers 2.x), Gradle(Groovy), PostgreSQL 16,
Spring Data JPA, Flyway, Spring Security(oauth2-resource-server의 Nimbus JWT), springdoc-openapi 3.x, JUnit5

## 기준 문서 (구현 전에 관련 부분을 반드시 읽을 것)
- 제품 동작: docs/pdf/72-02 · 기능명세서-*.pdf (기능명세서 v3.2) - 최우선
- 데이터: docs/pdf/72-05 · 데이터·ERD 설계 초안-*.pdf / API: docs/pdf/72-06 · API 계약 초안-*.pdf /
  범위: docs/pdf/72-01 _ PRD-*.pdf
- 분담과 인터페이스: docs/backend-split.md
- PDF는 `pdftotext -enc UTF-8 -layout <파일> <출력>`으로 텍스트를 뽑아 읽는다 (인코딩 지정 없으면 한글이 깨짐)
- 우선순위: 기능명세서 > ERD·API 초안. 문서끼리 충돌하거나 문서에 없는 결정이 필요하면
  임의로 정하지 말고 "결정 필요"로 나에게 먼저 물어볼 것

## 내 담당 (개발자 A)
인증·온보딩, 매장·소속·초대코드, 근무 조회·일괄등록·삭제, 로그아웃·매장 나가기·탈퇴, Spring Security,
현재 사용자·소속 해석
- B 담당 패키지(com.baton.substitute, com.baton.notification)와 common의 응답/에러/멱등성 코드는
  수정하지 말 것. 필요하면 수정 제안만 할 것
- B와 연결은 docs/backend-split.md의 인터페이스로만. B 구현이 아직 없으면
  com.baton.integration.stub 패키지에 @ConditionalOnMissingBean 스텁 빈을 둘 것

## 범위 제외 (만들지 말 것)
근무 수정 API(PATCH), 근무 변경 이력 조회, 초대코드 재발급, 외부 공유 링크, 푸시, 로그인 없는 체험 모드,
매니저의 대타 요청/후보 참여, 마지막 매니저 특수 처리

## 패키지 구조
com.baton
  common      (B 소유: ApiResponse, ErrorCode, BusinessException, GlobalExceptionHandler, idempotency)
  config      (Security, Clock, Jackson, OpenAPI)
  auth        (소셜 로그인, 토큰, 세션)
  member      (User, 동의, 온보딩, /me)
  store       (Store, Membership, InviteCode)
  shift       (Shift, ShiftEvent, 일괄 등록)
  account     (나가기, 탈퇴)
  integration (B와의 인터페이스, 스텁)
각 도메인 내부: api(Controller, Request/Response DTO) / application(Service) / domain(Entity, Repository)

## 코딩 규칙
- 권한: 요청 본문의 userId/role/membershipId를 권한 근거로 쓰지 않는다. 인증 정보와 DB의 활성 소속으로 판정
- 시간: DB는 timestamptz, Java는 Instant. 월/날짜/요일 계산만 ZoneId.of("Asia/Seoul").
  현재 시각은 항상 주입받은 Clock에서 가져온다 (Instant.now() 직접 호출 금지)
- JSON 필드는 snake_case. 응답은 {"data": ..., "meta": {"server_time": ...}} 형식.
  응답 본문이 없는 성공은 204 No Content
- 에러는 ErrorCode enum + BusinessException. 새 에러 코드가 필요하면 추가 제안 후 사용
- 동시성: 수정 대상 엔티티는 @Version. 중복 방지는 조회 후 검사만으로 끝내지 말고
  DB 유니크/부분 유니크 인덱스로 보장하고 DataIntegrityViolationException을 도메인 에러로 변환
- 잠금 순서: membership → shift → substitute_request (교착 방지, B와 공통 규칙)
- 스키마 변경은 새 Flyway 마이그레이션 파일로만. 기존 마이그레이션 수정 금지
- Flyway 번호는 V{yyyyMMddHHmm}__a_설명.sql처럼 타임스탬프로 작성 (예: V202611091530__a_add_invite_code_index.sql)
- 응답에 이메일, 소셜 식별자, 토큰 원문을 절대 포함하지 않는다
- 엔티티를 컨트롤러 응답으로 직접 반환하지 않는다 (record DTO 사용)
- enum 컬럼은 @Enumerated(EnumType.STRING) + DB CHECK 제약
  - 예외: consent_documents.kind는 문서 종류 미정으로 String (DB는 CHECK (kind ~ '^[A-Z_]{1,30}$'))
- Lombok은 @Getter, @NoArgsConstructor(access = PROTECTED), @RequiredArgsConstructor만 사용

## 테스트
- 서비스 로직: 단위 테스트, 시간은 Clock.fixed
- API: @SpringBootTest + Testcontainers PostgreSQL + MockMvc, local/test 프로필의 X-Test-User-Id 인증 사용
- 동시성: ExecutorService + CountDownLatch로 실제 동시 요청 테스트
- 완료 기준: ./gradlew test 전체 통과

## 작업 방식
- 구현 전에 계획(만들/수정할 파일, 테이블·인덱스, 트랜잭션 경계, 테스트 목록)을 먼저 보여줄 것
- 끝나면 다음 형식으로 보고: 1) 만든 API와 파일 2) 통과한 테스트 수 3) 문서와 다르게 하거나 임의로 정한 것
  4) B나 프론트와 합의가 필요한 것