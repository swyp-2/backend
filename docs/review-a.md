# 개발자 A 담당 기능 명세 대조 (review-a)

- 기준 문서: `docs/md/02 · 기능명세서.md`(v3.2), `docs/md/03 · 서비스 정책.md`(v2.0)
- 대상 코드: 브랜치 `feat/account-leave-withdraw` 작업 트리(2026-11-11, 커밋 안 된 OpenAPI 주석 변경 포함)
- 범위: COM-01~04, STR-01~03, SCH-01~05, SCH-07, PRO-01, PRO-03 + 관련 정책 P·B·D + 정책 10절 개발 검수 예시
- 코드는 수정하지 않았다
- 검토 시점 `./gradlew test`: 545건 중 실패 0, 오류 0, 건너뜀 1 (BUILD SUCCESSFUL). 아래 "검증 테스트"는 모두 이 실행에서 통과한 테스트다
- **2026-11-11 수정 반영(7-1 승인분)**: 수정 후 `./gradlew test` 551건 중 실패 0, 건너뜀 1. 반영된 행은 비고에 "[수정]"으로 표시했다

## 판정 기준

| 판정 | 의미 |
| --- | --- |
| 충족 | 서버가 명세 문장대로 동작한다. 검증 테스트가 없으면 비고에 **테스트 없음**을 적는다 |
| 부분 | A 쪽은 구현했지만 결과가 명세에 못 미친다(B 미구현 의존 포함), 또는 일부 조건이 빠졌다 |
| 누락 | 서버 쪽 구현이 없다 |
| 범위외 | 프론트 화면 동작(서버 할 일 없음), B 담당, 또는 이번 범위 제외 항목. 비고에 어느 쪽인지 적는다 |

- 테스트 이름은 `클래스#메서드`로 줄여 적는다(패키지 생략)
- "B 빈 구현"은 `SubstituteRequestLifecycleService`의 `closeBySourceDeleted`·`cleanupOnMembershipLeave`·`anonymizeFor`가
  아무 일도 하지 않는 현재 상태를 뜻한다(B의 `SubstituteRequestLifecycleServiceTest#아직_구현_전인_정리와_파기는_예외_없이_끝난다`로 확인됨)

## 요약

| 구분 | 건수 | 내용 |
| --- | --- | --- |
| 누락 | 2 (최초 3) | 탈퇴 재식별 가능 기록 삭제 기준, 백업 복원 시 재유입 차단. StoreManagerQuery A 구현은 [수정]으로 해결 |
| 부분 | 6 | 근무 삭제 시 요청 종료(B 빈 구현), 나가기·탈퇴 요청 정리(B 빈 구현), 탈퇴 시 수신 알림 파기(B 빈 구현), 탈퇴 후 membership_id 잔존, 운영 환경 약관 0건이면 필수 동의 없이 통과, 나가기 차단 응답에 남은 근무 정보 없음. 같은 문제가 정책(B17·B21·D05·D20·8절)과 10절 행에도 다시 나온다 |
| 범위 제외 실수 구현 | 0 | 노출된 API는 없음. 쓰이지 않던 재발급용 `InviteCode.invalidate()`는 [수정]으로 삭제 |
| 테스트 없음(충족) | 2 (최초 5) | SCH-01 예시 수치 하드코딩 없음, SCH-04 직원별 패턴 별도 등록(둘 다 구조로 보장). STR-02·SCH-02는 [수정]으로 테스트 추가, SCH-05 일부 겹침은 기존 테스트가 이미 확인하고 있었음(최초 검토 오류) |

---

## 1. 공통·매장

### COM-01 앱 진입·회원 상태 분기

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| COM-01 | 앱 진입은 로그인 화면에서 시작한다. | - | - | 범위외 | 프론트 화면 흐름 |
| COM-01 | 인증된 기존 회원은 가입 절차를 반복하지 않고 소속 매장의 근무표로 이동한다. | member/application/NextScreen.java:of (활성 소속이면 CA_01), MeService.java:assemble | NextScreenTest#활성_소속이_있으면_단계와_무관하게_근무표, OnboardingFlowApiTest#활성_소속이_있으면_CA01이고_소속_정보를_돌려준다 | 충족 | GET /me의 next_screen |
| COM-01 | 신규 회원은 약관·이름·역할·매장 연결을 순서대로 진행한다. | member/domain/User.java:agreeConsents·setName·selectRole·joinStore (requireStage) | UserTest#신규_회원은_동의_이름_역할_매장_순서로_진행한다, OnboardingFlowApiTest#신규_회원_매니저_흐름_CO02_CO04_CO05_ST01, #동의_전_이름_저장은_409, #동의_전_역할_저장은_409, #이름_전_역할_저장은_409 | 충족 | |
| COM-01 | 세션이 만료되면 로그인으로 이동한다. | auth/application/AccessTokenService.java:buildDecoder, auth/security/SessionCheckingJwtConverter.java:convert | AccessTokenAuthenticationTest#발급_후_14일이_지난_토큰은_401, #세션_만료_시각이_지나면_토큰이_남아_있어도_401 | 충족 | 서버는 401만 보장. 화면 이동은 프론트 |
| COM-01 | 기존 계정이어도 가입 도중 매장 연결을 마치지 않았거나 매장을 나가면 소속이 없을 수 있다. | User.java:leaveStore, NextScreen.java:of (STORE_REQUIRED) | OnboardingFlowApiTest#매장을_나간_계정은_저장된_역할_기준으로_복귀한다, UserTest#매장을_나가면_동의_이름_역할은_유지하고_매장_연결_단계로_돌아간다 | 충족 | |
| COM-01 | 동의·이름이 완료된 계정은 반복 입력 없이 저장된 매니저 역할이면 ST-01, 크루원이면 ST-03으로 이동한다. | NextScreen.java:of | OnboardingFlowApiTest#정책10절_이름_역할까지만_저장한_계정은_ST01_ST03으로_복귀하고_매장_데이터는_403 | 충족 | |
| COM-01 | 역할도 없으면 CO-05부터 이어간다. | NextScreen.java:of (ROLE_REQUIRED→CO_05) | OnboardingFlowApiTest#신규_회원_매니저_흐름_CO02_CO04_CO05_ST01 | 충족 | STORE_REQUIRED인데 역할이 null인 분기는 정상 흐름에서 생기지 않는 방어 코드 |
| COM-01 | 유효 코드로 참여하거나 매장을 만들기 전까지 다른 매장 데이터에 접근할 수 없다. | store/application/MembershipGuard.java:requireActive | OnboardingFlowApiTest#역할_저장만으로는_어떤_매장에도_접근할_수_없다, #정책10절_이름_역할까지만_저장한_계정의_근무표_조회는_403 | 충족 | |
| COM-01 | 로그인 없는 체험·예시 계정 전환은 제외한다. | config/SecurityConfig.java:securityFilterChain (anyRequest().authenticated) | AccessTokenAuthenticationTest#토큰이_없으면_401, ProdProfileSecurityTest#X_Test_User_Id_헤더로는_인증되지_않는다 | 범위외 | 범위 제외. 구현 없음 확인 |
| COM-01 | (완료) 신규/기존 회원을 실제 회원 상태로 분기한다. | MeService.java:assemble, NextScreen.java:of | OnboardingFlowApiTest 전체 | 충족 | |
| COM-01 | (완료) 기존 회원의 가입 정보를 덮어쓰지 않는다. | auth/application/SocialSignUp.java:findOrCreate, User.java:requireStage | SocialCompleteApiTest#기존_회원은_같은_계정으로_다시_로그인하고_세션만_새로_생긴다, OnboardingFlowApiTest#이름_재저장은_409이고_기존_이름을_덮어쓰지_않는다, ConsentApiTest#동의_재요청은_409이고_기존_기록을_덮어쓰지_않는다 | 충족 | |

### COM-02 Google·Apple 로그인

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| COM-02 | 선택한 소셜 인증을 완료한 뒤 서버의 회원·소속 정보를 조회한다. | auth/api/AuthController.java:complete → SocialLoginService.java:complete, social/JwksSocialTokenVerifier.java | SocialCompleteApiTest#처음_보는_소셜_계정이면_가입_진행_중_회원을_만들고_토큰을_발급한다, JwksSocialTokenVerifierTest 전체 | 충족 | 로그인 응답은 onboarding_stage만 주고 소속·next_screen은 GET /me로 받는다 |
| COM-02 | 신규 회원은 CO-02, 소속이 있는 기존 회원은 근무표로 이동한다. | NextScreen.java:of | OnboardingFlowApiTest#신규_회원_매니저_흐름_CO02_CO04_CO05_ST01, #활성_소속이_있으면_CA01이고_소속_정보를_돌려준다 | 충족 | |
| COM-02 | 인증 실패·취소 시 로그인에서 다시 시도한다. | SocialLoginService.java:complete (검증 실패 시 401, 회원 미생성) | SocialCompleteApiTest#검증에_실패한_토큰은_401이고_회원을_만들지_않는다, JwksSocialTokenVerifierTest#제공자_키_조회_실패는_재시도_가능한_서버_오류다 | 충족 | 취소는 프론트(SDK) |
| COM-02 | (예외 문장 중 소속 없음·ST-01/ST-03·CO-05·다른 매장 접근 불가) | COM-01과 같음 | COM-01과 같음 | 충족 | COM-01 행 참고 |
| COM-02 | 이메일·비밀번호·카카오 로그인과 외부 대타 링크 복귀는 범위에 없다. | AuthController.java:parseProvider (google·apple만) | SocialCompleteApiTest#지원하지_않는_제공자는_404 | 범위외 | 범위 제외. 구현 없음 확인 |
| COM-02 | (완료) 인증 성공 후에만 회원 데이터에 접근한다. | SecurityConfig.java:securityFilterChain, SessionCheckingJwtConverter.java:convert | AccessTokenAuthenticationTest#토큰이_없으면_401, #위조되거나_형식이_틀린_토큰은_401, SocialCompleteApiTest#인증_API가_아닌_auth_경로는_여전히_인증이_필요하다 | 충족 | |
| COM-02 | (완료) 실패를 로그인 성공으로 표시하지 않는다. | AuthController.java:complete (토큰 발급 전 검증) | SocialCompleteApiTest#검증에_실패한_토큰은_401이고_회원을_만들지_않는다, #id_token이_비어_있으면_422 | 충족 | |

### COM-03 약관 동의·약관 보기·이름 입력

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| COM-03 | 필수·선택 동의를 구분한다. | member/domain/ConsentDocument.java (required), ConsentService.java:getCurrentDocuments | ConsentApiTest#약관_조회는_필수와_선택_문서를_같은_형식으로_내려준다 | 충족 | |
| COM-03 | 전체 동의는 개별 선택과 연동한다. | - | - | 범위외 | 프론트 |
| COM-03 | 보기는 공통 CO-03에 해당 문서를 표시하고 닫으면 선택 상태를 유지한다. | ConsentDocument.java (body_reference) | ConsentApiTest#약관_조회는_필수와_선택_문서를_같은_형식으로_내려준다 | 충족 | 문서 URL 제공까지 서버. 선택 상태 유지는 프론트 |
| COM-03 | 다음에서 필수 동의를 검증·저장한 뒤 이름을 입력한다. | ConsentService.java:agree·validate, User.java:agreeConsents | ConsentApiTest#필수_미동의는_422이고_해당_문서가_field_errors에_있다, #필수_문서가_요청에_빠지면_422 | 충족 | |
| COM-03 | (입력) 문서별 동의 여부·버전·동의 시각을 기록한다. | member/domain/UserConsent.java:decide, ConsentService.java:agree | ConsentApiTest#요청에_없는_선택_문서는_false로_기록되고_시각은_서버_시각, #클라이언트가_보낸_시각_필드는_무시한다 | 충족 | 버전은 document_id로 연결(문서 행이 kind+version) |
| COM-03 | (입력) 앞뒤 공백 제거 후 이름 2~15자. | member/domain/DisplayName.java:normalize, DB users_display_name_len_ck | DisplayNameTest 전체, OnboardingNameRoleApiTest#이름은_앞뒤_공백을_제거해_저장한다, #이모지_포함_15자는_통과하고_16자는_거부 | 충족 | 제어문자·줄바꿈 거부는 임의로 더한 규칙 |
| COM-03 | 보기·닫기만으로 동의 처리하지 않는다. | ConsentDocumentController.java:documents (조회만) | ConsentApiTest#약관을_보기만_하고_저장하지_않으면_동의_기록은_0건 | 충족 | |
| COM-03 | 선택 미동의도 가입 가능하다. | ConsentService.java:validate | ConsentApiTest#선택_미동의는_통과하고_false로_기록된다 | 충족 | |
| COM-03 | 필수 미동의·이름 누락·범위 오류를 해당 위치에 안내한다. | ConsentService.java:fieldOf (`agreements[document_id=N]`), DisplayName.java:invalid (`display_name`) | ConsentApiTest#필수_미동의는_422이고_해당_문서가_field_errors에_있다, OnboardingNameRoleApiTest(빈 값·공백·16자 422) | 충족 | field 형식 `agreements[document_id=N]`은 프론트 합의 필요(코드 주석에도 표시) |
| COM-03 | 약관 명칭·본문은 미정이다. | db/seed/local/R__seed_consent_documents.sql (local·test 프로필만) | ConsentDocumentSeedTest#test_프로필에는_임시_약관_시드가_들어간다 | 부분 | **운영 프로필에는 약관 문서가 0건**이라 `POST /me/consents`에 빈 목록을 보내도 통과해 필수 동의 없이 NAME_REQUIRED로 넘어간다. 문서 0건 상황 테스트 없음. U-04 미정과 연결 → 결정 필요 |
| COM-03 | 선택 항목의 보기는 공통 상세 틀을 재사용하며 선택 동의 기능을 제거하지 않는다. | ConsentDocumentController.java:documents (필수·선택 같은 형식) | ConsentApiTest#약관_조회는_필수와_선택_문서를_같은_형식으로_내려준다 | 충족 | |
| COM-03 | (완료) 필수 동의와 유효한 이름을 저장한 경우만 다음으로 이동한다. | User.java:agreeConsents·setName | UserTest#유효하지_않은_이름은_단계를_바꾸지_않는다, #잘못된_단계에서는_이름이_유효하지_않아도_단계_충돌이_먼저다 | 충족 | |
| COM-03 | (완료) 시간표·프로필에 같은 풀네임을 표시한다. | users.display_name 단일 원천: MeService.java:assemble, MembershipRepository.java:findActiveMemberRows, ShiftRepository.java:ROW_SELECT | ShiftQueryApiTest#동시각_근무는_한국어_이름순_같으면_membership_id순, StoreMembersApiTest#all은_매니저_먼저_한국어_이름순_같으면_membership_id순 | 충족 | /me·members·shifts의 이름을 한 번에 비교하는 테스트는 없음(간접 검증) |

### COM-04 역할 선택

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| COM-04 | 매니저·크루원 중 하나를 선택한다. | member/application/OnboardingService.java:selectRole·parseRole | OnboardingNameRoleApiTest(잘못된 역할 값 422) | 충족 | |
| COM-04 | 매니저는 ST-01 매장 만들기, 크루원은 ST-03 매장 참여로 이동한다. | NextScreen.java:of | OnboardingFlowApiTest#신규_회원_매니저_흐름_CO02_CO04_CO05_ST01, #신규_회원_크루원_흐름은_ST03 | 충족 | |
| COM-04 | 역할 선택만으로 기존 매장의 매니저 권한을 얻지 못한다. | OnboardingService.java:selectRole (소속 생성 없음), StoreCommandService.java:join (항상 CREW 소속) | OnboardingNameRoleApiTest#역할_저장만으로_매장_소속이나_권한이_생기지_않는다, OnboardingFlowApiTest#활성_소속이_있는_사용자의_역할_요청은_409 | 충족 | |
| COM-04 | 가입 후 역할 변경은 확정 기능에 추가하지 않는다. | User.java:selectRole (ROLE_REQUIRED에서만) | OnboardingFlowApiTest#매장_연결_단계에서_역할_재선택은_409, UserTest#역할은_ROLE_REQUIRED에서만_STORE_REQUIRED_재선택도_거부 | 충족 | 매장을 나간 뒤에도 역할을 바꿀 수 없다(매니저→크루원 전환 경로 없음). 명세대로이나 PM 인지 필요 |
| COM-04 | (완료) 체험용 우회 분기는 없다. | - | - | 범위외 | 범위 제외. 구현 없음 확인 |

### STR-01 매장 만들기

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| STR-01 | 매장과 생성자의 매니저 소속을 함께 생성하고 ST-02 크루 초대로 이동한다. | store/application/StoreCommandService.java:create (매장+MANAGER 소속+초대 코드 한 트랜잭션), StoreController.java:create | StoreApiTest#매니저_가입_매장_생성_코드_확인 | 충족 | 응답에 invite_code 포함. 화면 이동은 프론트 |
| STR-01 | 앞뒤 공백 제거 후 매장명 1~15자. 빈 값·16자 이상을 차단한다. | store/domain/StoreName.java:normalize, DB stores_name_len_ck | StoreNameTest 전체, StoreApiTest#매장명_검증_실패는_422_name | 충족 | |
| STR-01 | 재시도·연속 선택으로 같은 매장을 중복 생성하지 않는다. | StoreController.java:create (Idempotency-Key 필수), StoreCommandService.java:create (user 잠금, ALREADY_IN_STORE), DB memberships_user_active_uk | StoreApiTest#같은_키_재요청은_처음_결과_그대로_매장_1개, #다른_키로_동시에_두_번_와도_매장은_1개, #Idempotency_Key가_없으면_422 | 충족 | |
| STR-01 | (완료) 성공 시 매장과 매니저 소속이 모두 저장되고 실패 시 입력값을 유지한다. | StoreCommandService.java:create (@Transactional) | InviteCodeIssueRetryTest#다섯_번_모두_충돌하면_500이고_아무것도_남지_않는다 | 충족 | 입력 유지는 프론트 |
| STR-01 | (권한) 매니저 역할 가입자만 만든다. | StoreCommandService.java:requireStoreStage | StoreApiTest#크루원_역할이나_매장_연결_전_단계면_403, #이미_매장이_있으면_409_ALREADY_IN_STORE | 충족 | API 초안의 "매니저 역할 가입자" |

### STR-02 초대 코드 확인·복사

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| STR-02 | 매장 공통 초대 코드 한 개를 표시한다. | StoreQueryService.java:getInviteCode, DB invite_codes_store_valid_uk | MembershipRepositoryTest#매장별_유효_초대코드는_하나다, StoreApiTest#매니저_가입_매장_생성_코드_확인 | 충족 | |
| STR-02 | (복사) 복사 성공은 성공 안내, 실패는 길게 눌러 직접 복사하도록 안내한다. | - | - | 범위외 | 프론트 |
| STR-02 | 코드는 영문 대문자·숫자 8자리다. | store/domain/InviteCodeGenerator.java:generate, DB invite_codes_code_format_ck | InviteCodeGeneratorTest#영문_대문자_숫자_8자리, MembershipRepositoryTest#초대코드는_영문_대문자와_숫자_8자리만_허용한다 | 충족 | 충돌 시 최대 5회 재시도(InviteCodeIssueRetryTest) |
| STR-02 | 코드 복사 후에도 현재 화면을 유지하고 근무표 시작하기는 CA-02로 연결한다. | - | - | 범위외 | 프론트 |
| STR-02 | 매장이 유지되는 동안 사용한다. | InviteCode.java (만료 없음, invalidated_at null 유지) | MembershipJoinApiTest#초대_코드는_발급_후_시간이_지나도_매장이_있는_동안_유효하다 | 충족 | [수정] 테스트 추가 |
| STR-02 | 매니저만 코드에 접근한다. | StoreQueryService.java:getInviteCode → MembershipGuard.java:requireManager | StoreApiTest#크루원은_초대_코드를_볼_수_없다, #다른_매장의_정보_코드_구성원은_403 | 충족 | |
| STR-02 | 코드 재발급 UI·확인 팝업은 이번 범위에서 제외한다. | 재발급 API 없음 | - | 범위외 | 범위 제외. API 없음 확인. [수정] 미사용 재발급용 메서드 `InviteCode.invalidate` 삭제 |
| STR-02 | (완료) 표시된 코드와 복사되는 코드가 같으며 복사 실패를 성공으로 표시하지 않는다. | - | - | 범위외 | 프론트. 서버는 코드 하나만 돌려준다 |
| STR-02 | (완료) BATON726은 시안 예시이며 고정 운영 코드가 아니다. | InviteCodeGenerator.java:generate (SecureRandom) | InviteCodeGeneratorTest#영문_대문자_숫자_8자리 | 충족 | |

### STR-03 초대 코드로 매장 참여

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| STR-03 | 유효한 코드를 검증해 매장 소속을 즉시 생성하고 CA-01로 이동한다. | StoreCommandService.java:join, MembershipController.java:join | MembershipJoinApiTest#크루원_가입_코드_참여_후_next_screen은_CA01 | 충족 | 참여 응답에는 next_screen이 없고 GET /me로 확인 |
| STR-03 | 별도의 매니저 참여 승인·대기 단계는 없다. | Membership.java:join (바로 ACTIVE) | MembershipJoinApiTest#크루원_가입_코드_참여_후_next_screen은_CA01 | 충족 | |
| STR-03 | (입력) 영문 대문자·숫자 8자리 코드. | StoreCommandService.java:normalizeCode | MembershipJoinApiTest#형식_오류는_422_VALIDATION_FAILED_소문자도_거부 | 충족 | 앞뒤 공백은 제거 후 검사(임의 결정). 소문자 자동 변환 없음 |
| STR-03 | 빈 값·형식 오류·유효하지 않은 코드를 구분한다. | StoreCommandService.java:normalizeCode (빈 값·형식: 422 VALIDATION_FAILED, 메시지 다름), join (없는·무효 코드: 422 INVALID_INVITE_CODE) | MembershipJoinApiTest#형식_오류는_422_VALIDATION_FAILED_소문자도_거부(빈 값 포함), #없는_코드는_422_INVALID_INVITE_CODE, #무효화된_코드는_INVALID_INVITE_CODE | 충족 | 빈 값과 형식 오류는 에러 코드가 같고 메시지로만 구분된다. [수정] MembershipJoinApiTest#빈_값과_형식_오류는_같은_필드에서_다른_문구로_구분한다 추가 |
| STR-03 | 같은 매장에 이미 소속된 경우 중복 생성하지 않는다. | StoreCommandService.java:join (기존 소속 반환), DB memberships_store_user_active_uk | MembershipJoinApiTest#같은_매장_재참여는_기존_소속을_200으로, MembershipRepositoryTest#같은_매장에_활성_소속이_중복되면_DB가_거부한다 | 충족 | 다른 매장 소속이면 409 ALREADY_IN_STORE |
| STR-03 | 코드 없이 체험하는 진입은 없다. | - | - | 범위외 | 범위 제외. 구현 없음 확인 |
| STR-03 | (완료) 성공한 계정만 해당 매장 근무표에 접근하고 실패 시 입력을 유지한다. | MembershipGuard.java:requireActive | OnboardingFlowApiTest#정책10절_이름_역할까지만_저장한_계정의_근무표_조회는_403 | 충족 | 입력 유지는 프론트 |
| STR-03 | (권한) 크루원 역할 가입자만 참여한다. | StoreCommandService.java:join (selectedRole == CREW) | MembershipJoinApiTest#매니저_역할이나_역할_선택_전이면_403 | 충족 | |

---

## 2. 근무표·근무 관리

### SCH-01 내 근무·전체 근무 달력

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-01 | 내/전체 근무를 전환한다. | shift/api/ShiftQueryController.java:list (scope=mine/all), ShiftQueryService.java:getMonth | ShiftQueryApiTest#mine은_월_전체_내_근무만_시작순이고_all은_매장_기준_인원에_목록은_빈_배열 | 충족 | |
| SCH-01 | 이전·다음 달 및 오늘로 이동한다. | ShiftQueryController.java:list (month 파라미터) | ShiftQueryApiTest#잘못된_파라미터는_422 | 충족 | 이동 UI는 프론트. "오늘"은 meta.server_time 기준 가능 |
| SCH-01 | 달력에는 날짜별 중복 제거한 근무 인원수를 표시한다. | ShiftQueryService.java:dayCounts | ShiftQueryApiTest#같은_사람_같은_날_2건은_인원_1명_건수_2건 | 충족 | scope=mine의 days는 "내 근무" 기준(날짜마다 0 또는 1). 내 탭 달력에도 매장 인원을 보여야 하는지 프론트 확인 필요 |
| SCH-01 | 내 근무 아래 목록은 표시 중인 월 전체를 따라간다. | ShiftQueryService.java:getMonth (MINE: 월 전체 목록) | ShiftQueryApiTest#mine은_월_전체_내_근무만_시작순이고_all은_매장_기준_인원에_목록은_빈_배열, #월경계_야간_근무는_시작한_달에만_나온다, #월_경계_자정_정각_시작은_다음_달에_속한다 | 충족 | |
| SCH-01 | 내/전체 탭은 선택과 좌우 스와이프 모두 지원하며 같은 탭 상태로 동기화한다. | - | - | 범위외 | 프론트(D21) |
| SCH-01 | 날짜를 눌러도 내 근무 월 목록을 하루로 줄이지 않는다. | ShiftQueryController.java:list (date 조회는 별도 응답 ShiftDayResponse) | ShiftQueryApiTest#날짜_조회는_scope를_무시하고_삭제된_근무를_뺀다 | 충족 | |
| SCH-01 | 로딩·0건·실패를 구분한다. | 0건은 빈 배열, 실패는 ErrorResponse | - | 범위외 | 프론트 표시. 서버 0건 응답 테스트는 없음 |
| SCH-01 | 매니저 하단 메뉴는 근무표·프로필 및 추가(+), 크루원은 근무표·요청·프로필이다. | - | - | 범위외 | 프론트 |
| SCH-01 | (완료) 본인·소속 매장 기준으로 실제 데이터에서 인원과 목록을 계산한다. | ShiftQueryService.java:getMonth (requireActive + DB) | ShiftQueryApiTest#다른_매장_구성원은_목록을_볼_수_없다 | 충족 | |
| SCH-01 | (완료) 시안의 날짜·12개 등 예시 수치는 고정값으로 사용하지 않는다. | 하드코딩 없음 | - | 충족 | **테스트 없음**. 코드 검색으로 확인 |

### SCH-02 날짜별 명단·근무 상세

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-02 | 날짜 선택 시 그날 전체 근무를 팝업으로 보여준다. | ShiftQueryService.java:getDay | ShiftQueryApiTest#일곱_명_있는_날은_일곱_명_모두_반환 | 충족 | |
| SCH-02 | 시작 시각 순으로 이름·개인 색상·시간을 표시한다. | ShiftQueryService.java:getDay·toItem | ShiftQueryApiTest#동시각_근무는_한국어_이름순_같으면_membership_id순 | 충족 | |
| SCH-02 | 명단 선택 시 역할에 맞는 근무 상세로 이동한다. | ShiftQueryService.java:getDetail, ShiftActions.java:of | ShiftQueryApiTest#크루원_내_미래_근무_상세, #크루원이_타인_근무를_보면_행동_없고_매니저는_과거_근무도_삭제_가능 | 충족 | |
| SCH-02 | 닫으면 선택 월·날짜를 유지한다. | - | - | 범위외 | 프론트 |
| SCH-02 | 매니저 SH-02에서 승인 대기 요청이 있으면 "대타 승인 요청 보기"로 AP-02에 진입할 수 있다. | ShiftActions.java:of (VIEW_APPROVAL_REQUEST), ShiftQueryService.java:getDetail (active_request) | ShiftActionsTest#매니저는_승인_대기_요청이_있으면_승인_요청_보기_추가, ShiftQueryServiceTest#매니저는_시작_후에도_삭제_가능하고_승인_대기면_승인_요청_보기 | 충족 | |
| SCH-02 | 알림 7일 만료 후에도 이 진입을 유지한다. | SubstituteRequestLifecycle.findActiveByShift (요청 테이블만 조회, 알림과 무관) | ShiftDetailApprovalEntryTest#승인_요청_알림이_만료돼_지워져도_매니저_근무_상세에서_승인_요청_보기가_남는다 | 충족 | [수정] 실제 B 빈·알림 정리 작업으로 확인하는 테스트 추가 |
| SCH-02 | (입력) 근무자 수·근무 건수를 표시한다. | ShiftQueryService.java:getDay (headcount, shiftCount) | ShiftQueryApiTest#같은_사람_같은_날_2건은_인원_1명_건수_2건 | 충족 | |
| SCH-02 | 명단은 5명으로 자르지 않고 스크롤한다. | ShiftQueryService.java:getDay (개수 제한 없음) | ShiftQueryApiTest#일곱_명_있는_날은_일곱_명_모두_반환 | 충족 | |
| SCH-02 | 매니저만 날짜별 추가와 근무 삭제가 가능하다. | ShiftBatchService.java:requireManager, ShiftCommandService.java:delete (requireManager) | ShiftBatchApiTest#크루원은_미리보기도_등록도_403, ShiftDeleteApiTest#크루원은_본인_근무도_삭제할_수_없다 | 충족 | |
| SCH-02 | 크루원의 대타 요청은 시작 전 본인 근무에만 제공한다. | ShiftActions.java:of, ShiftQueryService.java:getDetail (Clock 기준 isStarted) | ShiftQueryServiceTest#크루원_내_근무_시작_1분_전이면_대타_요청_가능, #크루원_내_근무_시작_1분_후면_행동_없음, #시작_시각_정각이면_시작한_것으로_본다 | 충족 | |
| SCH-02 | 타인 근무 상세에는 요청 버튼이 없다. | ShiftActions.java:of | ShiftActionsTest#크루원이_타인_근무를_보면_행동_없음 | 충족 | |
| SCH-02 | 동시각 정렬은 한국어 이름·소속 ID 순을 유지한다. | ShiftQueryService.java:getDay (Collator KOREAN → membership_id) | ShiftQueryApiTest#동시각_근무는_한국어_이름순_같으면_membership_id순 | 충족 | |
| SCH-02 | (완료) 선택한 근무의 ID·날짜·시간·담당자가 상세와 다음 요청 화면까지 이어진다. | ShiftDetailResponse (shift_id, starts_at, ends_at, local_date, assignee, version) | ShiftQueryApiTest#크루원_내_미래_근무_상세 | 충족 | |
| SCH-02 | (완료) 매니저 대타 요청·근무 수정 버튼은 없다. | ShiftActions.java:of (매니저: delete, view_approval_request만) | ShiftQueryApiTest#매니저_본인_근무에도_대타_요청_행동은_없다, ShiftActionsTest#매니저는_과거_근무도_삭제만_가능하고_대타_요청은_없다 | 충족 | |

### SCH-03 복수 근무자 근무 추가

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-03 | 같은 매장의 현재 소속 구성원 1명 이상을 선택한다. | ShiftPlanCalculator.java:validateAssignees, ShiftBatchService.java:requireAllActive | ShiftPlanCalculatorTest#invalidAssignees, ShiftBatchApiTest#다른_매장_구성원이나_나간_구성원이_있으면_422 | 충족 | |
| SCH-03 | 시작·종료 날짜와 시간을 입력한다. | ShiftBatchInput (starts_at, ends_at) | ShiftPlanCalculatorTest#missingFields | 충족 | |
| SCH-03 | 근무자 팝업의 선택 적용은 입력값만 반영한다. | - | - | 범위외 | 프론트 |
| SCH-03 | 등록 내용 확인은 ED-03으로 이동하며 아직 저장하지 않는다. | ShiftBatchService.java:preview (readOnly) | ShiftBatchApiTest#미리보기는_아무것도_쓰지_않는다 | 충족 | |
| SCH-03 | 근무 배정 대상에는 매니저도 포함할 수 있다. | ShiftBatchService.java:requireAllActive (역할 무관) | ShiftBatchApiTest#매니저_본인도_근무자로_등록할_수_있다 | 충족 | |
| SCH-03 | 0명·필수 누락·잘못된 시간은 차단한다. | ShiftPlanCalculator.java:validateAssignees·validateTimes | ShiftPlanCalculatorTest#invalidAssignees, #missingFields, #lengthBounds, ShiftBatchApiTest#입력이_잘못되면_미리보기도_422 | 충족 | |
| SCH-03 | 길이는 0분 초과·24시간 미만이고 자정을 넘으면 종료일을 다음 날로 입력한다. | ShiftPlanCalculator.java:validateTimes, DB shifts_duration_ck | ShiftPlanCalculatorTest#lengthBounds, #overnightRepeat, ShiftRepositoryTest#정확히_24시간_근무는_check_제약으로_막힌다 | 충족 | 분 단위 시각만 허용(2026-11-10 임의 결정, ShiftPlanCalculatorTest#wholeMinutesOnly) |
| SCH-03 | 완전 동일 중복은 새로 생성하지 않는다. | ShiftBatchService.java:existingKeys·insertShifts, DB shifts_identical_live_uk | ShiftRepositoryTest#같은_매장_담당자_시간의_유효_근무는_중복_저장되지_않는다, ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| SCH-03 | (완료) 선택 이름·인원·시간을 미리보기까지 유지한다. | ShiftBatchService.java:preview (PreviewItem: display_name, starts_at, ends_at) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| SCH-03 | (완료) 입력 화면과 팝업 적용만으로 근무가 생성되지 않는다. | ShiftBatchService.java:preview (쓰기 없음) | ShiftBatchApiTest#미리보기는_아무것도_쓰지_않는다 | 충족 | |
| SCH-03 | (완료) 수정 모드는 없다. | PATCH/PUT /shifts 없음, Shift 시각 컬럼 updatable=false | - | 범위외 | 범위 제외. 구현 없음 확인 |

### SCH-04 반복 근무 설정

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-04 | 사용 여부·기간·요일을 선택한다. | ShiftBatchInput.Repeat (enabled, start_date, end_date, weekdays) | ShiftPlanCalculatorTest#apiExample, #noRepeat_threeAssignees | 충족 | |
| SCH-04 | 설정 적용은 ED-01로 복귀해 입력에 반영하며 저장하지 않는다. | - | - | 범위외 | 프론트 |
| SCH-04 | 최종 등록 때 선택 구성원 모두에게 같은 요일·시간·기간을 적용한 날짜별 독립 근무를 생성한다. | ShiftPlanCalculator.java:plan (날짜×담당자), ShiftBatchService.java:create (행마다 Shift) | ShiftPlanCalculatorTest#apiExample, ShiftDeleteApiTest#반복_등록한_근무_중_하루를_지워도_다른_날짜는_남는다 | 충족 | 반복 시작일은 첫 근무 날짜와 같아야 함(임의 결정, #repeatStartMismatch). 한 번에 최대 2000건(임의 결정, #maxPlannedShifts) |
| SCH-04 | 요일 1개 이상. | ShiftPlanCalculator.java:validateWeekdays | ShiftPlanCalculatorTest#invalidWeekdays | 충족 | 요일 값 1=월~7=일(B22) |
| SCH-04 | 종료일 역전·요일 0개·기간 내 선택 요일 없음은 안내한다. | ShiftPlanCalculator.java:validateRepeat | ShiftPlanCalculatorTest#missingFields(역전 종료일), #invalidWeekdays | 충족 | |
| SCH-04 | 반복 기간은 달력상 1개월이다. 종료일은 시작일에 달력상 1개월을 더한 날짜 이하이며 시작일·종료일 모두 포함한다. | ShiftPlanCalculator.java:validateRepeat (start.plusMonths(1), datesUntil(end+1)) | ShiftPlanCalculatorTest#maxEndDate_normalMonth (11-04→12-04 허용, 12-05 거부) | 충족 | |
| SCH-04 | 다음 달에 같은 일자가 없으면 다음 달 말일을 상한으로 한다. | 같은 위치 (plusMonths 말일 보정) | ShiftPlanCalculatorTest#maxEndDate_monthEnd (01-31→02-28 허용, 03-01 거부) | 충족 | |
| SCH-04 | 30일 또는 31일 고정 일수로 계산하지 않는다. | 같은 위치 | 위 두 테스트 | 충족 | |
| SCH-04 | 직원별 다른 패턴은 별도 등록한다. | ShiftBatchInput (공통 패턴 하나) | - | 충족 | **테스트 없음**. 한 요청에 패턴이 하나뿐인 구조로 보장 |
| SCH-04 | (완료) 적용·뒤로가기만으로 근무가 생성되지 않는다. | ShiftBatchService.java:preview | ShiftBatchApiTest#미리보기는_아무것도_쓰지_않는다 | 충족 | |
| SCH-04 | (완료) 최종 미리보기의 일자와 건수에 반복 설정이 반영된다. | ShiftBatchService.java:preview (items, total_count) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |

### SCH-05 등록 미리보기·최종 저장

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-05 | 근무자·일자·시간·새 근무 수·동일 중복 제외 수를 보여준다. | ShiftBatchService.java:preview (create_count, duplicate_count, total_count, items) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| SCH-05 | 최종 등록 확인에서만 새 근무를 저장한다. | ShiftBatchService.java:create (preview_token 필수, PreviewTokenCodec.verify) | ShiftBatchApiTest#토큰이_없으면_422, #토큰_변조_만료_다른_입력_다른_매니저면_PREVIEW_STALE | 충족 | 미리보기 토큰 30분(임의 결정, #토큰은_30분까지_유효하다) |
| SCH-05 | 성공 즉시 매장 구성원의 근무표에 반영한다. | ShiftBatchService.java:create (게시 단계 없음) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| SCH-05 | 모두 중복이면 등록할 수 없다. | ShiftBatchService.java:create (toCreate 비면 422) | ShiftBatchApiTest#전부_중복이면_미리보기는_0건으로_보여주고_최종_등록은_422 | 충족 | |
| SCH-05 | 저장은 전부 성공하거나 전부 실패하도록 처리한다. | ShiftBatchService.java:create (@Transactional), insertShifts | ShiftBatchApiTest#겹치는_계획을_동시에_최종_등록하면_한쪽만_성공하고_부분_저장이_없다, #미리보기_후_다른_매니저가_그중_1건을_등록하면_PREVIEW_STALE이고_저장_0건 | 충족 | |
| SCH-05 | 재시도로 중복 생성하지 않는다. | ShiftBatchController.java:create (Idempotency-Key 필수) | ShiftBatchApiTest#같은_키로_두_번_보내면_처음_결과_그대로이고_52건이_되지_않는다, #Idempotency_Key가_없으면_422 | 충족 | |
| SCH-05 | 실패 시 입력을 유지한다. | - | - | 범위외 | 프론트 |
| SCH-05 | 동일 중복은 같은 매장·담당자·시작·종료가 같은 경우다. | ShiftKey, DB shifts_identical_live_uk(store_id, assignee_membership_id, starts_at, ends_at) | ShiftRepositoryTest#같은_매장_담당자_시간의_유효_근무는_중복_저장되지_않는다, #같은_시간이라도_담당자가_다르면_저장된다, #삭제된_근무와_같은_시간은_다시_저장된다 | 충족 | |
| SCH-05 | 일부 시간만 겹치는 근무의 자동 차단은 기존 범위에서 제외한다. | ShiftRepository.java:findLiveKeys (완전 일치만) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다(11-16 10:00 일부 겹침 근무가 있어도 26건 생성) | 충족 | 최초 검토에서 "테스트 없음"으로 잘못 적었음 |
| SCH-05 | (완료) 실제 새로 저장된 건수만 결과로 표시한다. | ShiftBatchService.java:create (Created.createdCount) | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| SCH-05 | (완료) 실패를 완료로 표시하지 않고 별도 게시·근무자 수락 단계를 두지 않는다. | 에러 응답, 게시 상태 컬럼 없음 | ShiftBatchApiTest#미리보기_후_중복이던_근무가_삭제돼도_PREVIEW_STALE | 충족 | |

### SCH-07 근무 한 건 삭제

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SCH-07 | 삭제 확인 팝업에서 확정하면 선택한 근무 한 건을 제거한다. | ShiftCommandController.java:delete, ShiftCommandService.java:delete (소프트 삭제) | ShiftDeleteApiTest#삭제하면_날짜_조회와_월_조회에서_사라지고_상세는_404 | 충족 | 팝업은 프론트 |
| SCH-07 | 그 근무의 진행 중 대타 요청도 종료하고 기록을 함께 저장한다. | A: ShiftCommandService.java:delete (shift_events DELETED 기록 + closeBySourceDeleted 호출) / B: SubstituteRequestLifecycleService.java:closeBySourceDeleted **빈 구현** | ShiftCommandServiceTest#잠금_후_삭제하고_DELETED를_기록한_뒤_요청을_종료한다(목) | 부분 | A 쪽 호출·원자성은 충족. B가 아직 요청을 닫지 않아 삭제된 근무의 요청이 WAITING/PENDING_MANAGER로 남는다. 실제 요청이 CANCELLED_SOURCE_CHANGED가 되는지 확인하는 API 테스트 없음 |
| SCH-07 | 성공 후 해당 날짜 근무표를 갱신한다. | 같은 트랜잭션 커밋 | ShiftDeleteApiTest#삭제하면_날짜_조회와_월_조회에서_사라지고_상세는_404 | 충족 | 재조회는 프론트 |
| SCH-07 | (입력) 근무 ID·최신 상태, 진행 요청 종료 안내. | DELETE ?expected_version 필수, ShiftDeleteResponse.closed_request_id, 상세의 active_request | ShiftDeleteApiTest#expected_version이_없으면_422, ShiftCommandServiceTest#종료한_요청이_없으면_closed_request_id는_null | 충족 | B 빈 구현이라 지금은 closed_request_id가 항상 null |
| SCH-07 | 확인 취소·돌아가기는 변경 없이 상세로 복귀한다. | - | - | 범위외 | 프론트 |
| SCH-07 | 이미 삭제·담당자 변경 등 최신 상태가 다르면 재확인한다. | ShiftConflicts.java:requireLive (409 DELETED/CHANGED) | ShiftDeleteApiTest#오래된_expected_version이면_409_CHANGED이고_근무는_그대로, #이미_삭제된_근무를_다시_삭제하면_409_DELETED, ShiftAssignmentServiceTest#삭제와_담당자_변경이_동시에_일어나면_하나만_성공한다 | 충족 | |
| SCH-07 | 반복 등록한 다른 날짜에는 전파하지 않는다. | Shift 행 단위 삭제 | ShiftDeleteApiTest#반복_등록한_근무_중_하루를_지워도_다른_날짜는_남는다 | 충족 | |
| SCH-07 | 과거 근무도 매니저 삭제 가능하다. | ShiftCommandService.java:delete (시작 여부 검사 없음) | ShiftDeleteApiTest#과거_근무도_삭제할_수_있다 | 충족 | |
| SCH-07 | (완료) 확정 전에는 삭제하지 않는다. | 단일 DELETE 호출 | - | 범위외 | 프론트 |
| SCH-07 | (완료) 삭제와 관련 요청 종료가 서로 어긋나지 않는다. | ShiftCommandService.java:delete (@Transactional) | ShiftDeleteRollbackTest#요청_종료에서_예외가_나면_근무는_삭제되지_않고_기록도_없다 | 충족 | 원자성은 충족. 실제 종료는 위 "부분" 행 참고 |
| SCH-07 | (완료) 실패 시 완료로 표시하지 않는다. | 에러 응답 | ShiftDeleteApiTest#매니저_두_명이_같은_근무를_동시에_삭제하면_하나만_성공하고_기록은_1건 | 충족 | |
| SCH-07 | (권한) 다른 매장·크루원은 삭제할 수 없다. | ShiftCommandService.java:delete (requireManager, 잠금 전 권한 확인) | ShiftDeleteApiTest#다른_매장_매니저는_403이고_상태를_알려주지_않는다, ShiftCommandServiceTest#권한이_없으면_잠그지도_않는다 | 충족 | |

---

## 3. 프로필·계정

### PRO-01 프로필·매장 구성원 조회

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| PRO-01 | 본인 이름·역할·매장을 표시한다. | MeController.java:me → MeService.java:getMe | OnboardingFlowApiTest#활성_소속이_있으면_CA01이고_소속_정보를_돌려준다 | 충족 | |
| PRO-01 | 매장 정보에서 실제 구성원 수·풀네임·역할·개인 색상을 조회한다. | StoreQueryService.java:getStore (member_count), getMembers | StoreApiTest#매니저_가입_매장_생성_코드_확인, StoreMembersApiTest#all은_매니저_먼저_한국어_이름순_같으면_membership_id순 | 충족 | API 초안의 "소속 상태" 필드는 없음(ACTIVE만 반환하므로 생략) |
| PRO-01 | 매니저만 초대 코드 확인·복사가 가능하다. | STR-02와 같음 | StoreApiTest#크루원은_초대_코드를_볼_수_없다 | 충족 | |
| PRO-01 | 크루원의 내 요청 기록은 RQ-02로 연결한다. | B의 GET /me/substitute-requests | - | 범위외 | B 담당 |
| PRO-01 | 다른 사용자의 이메일·소셜 계정 정보를 노출하지 않는다. | 이메일은 저장하지 않음(social_accounts에 email 컬럼 없음), StoreController.MemberResponse에 식별자 없음 | OnboardingFlowApiTest#온보딩_응답에는_이메일_소셜_식별자_토큰이_없다, StoreMembersApiTest#all은_매니저_먼저_한국어_이름순_같으면_membership_id순(항목 필드 집합 고정) | 충족 | 최초 검토에서 구성원 목록 테스트를 빠뜨렸음 |
| PRO-01 | 사용자 이름·구성원 수를 시안 데이터로 하드코딩하지 않는다. | MembershipRepository.java:countByStoreIdAndState | StoreMembersApiTest(member_count 5 확인) | 충족 | |
| PRO-01 | 변경 이력 메뉴는 양쪽 모두 제외한다. | shift_events 조회 API 없음(ShiftEventRepository에 조회 메서드 없음) | - | 범위외 | 범위 제외. 구현 없음 확인 |
| PRO-01 | (완료) 권한에 맞는 메뉴를 보여준다. 매니저에게 내 요청 기록과 대타 요청 메뉴를 제공하지 않는다. | /me의 membership.role, ShiftActions.java:of | ShiftActionsTest#매니저는_과거_근무도_삭제만_가능하고_대타_요청은_없다 | 충족 | 메뉴 표시는 프론트 |

### PRO-03 로그아웃·매장 나가기·탈퇴

| 기능 ID | 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| PRO-03 | 로그아웃은 세션을 종료하고 CO-01로 이동한다. | AuthController.java:logout → SessionService.java:logout | LogoutApiTest#로그아웃하면_204이고_같은_액세스_토큰은_즉시_401 | 충족 | |
| PRO-03 | 매장 나가기·탈퇴 확정 직전에 아직 종료되지 않은 본인 배정 근무(예정·진행 중)를 다시 확인한다. | AccountService.java:releaseActive (소속 잠금 후 ShiftRepository.existsUnfinishedByAssignee, ends_at > now) | StoreLeaveApiTest#내일_근무가_남은_크루원은_나갈_수_없고_아무것도_바뀌지_않는다, #지금_진행_중인_근무가_있어도_나갈_수_없다, WithdrawApiTest 같은 두 건 | 충족 | |
| PRO-03 | 남아 있으면 현재 예정 근무 안내를 표시하고 처리하지 않는다. | AccountService.java:releaseActive (409 FUTURE_SHIFT_REMAINS) | AccountServiceTest#끝나지_않은_근무가_있으면_FUTURE_SHIFT_REMAINS이고_아무것도_바꾸지_않는다 | 부분 | 차단은 충족. 409 응답에 남은 근무 건수·목록이 없어 안내 화면이 근무를 보여 주려면 프론트가 GET /stores/{id}/shifts?scope=mine을 여러 달 조회해야 한다. 프론트 합의 필요 |
| PRO-03 | 없으면 확인 후 소속을 해제한다. | Membership.java:leave·withdraw | StoreLeaveApiTest#종료된_과거_근무와_삭제된_근무만_있으면_나가고_계정은_유지된다 | 충족 | |
| PRO-03 | 나가기는 계정을 유지하고 탈퇴는 계정을 종료한다. | AccountService.java:leave (세션 유지) / withdraw (User.withdraw, 세션 삭제) | StoreLeaveApiTest#종료된_과거_근무와_삭제된_근무만_있으면_나가고_계정은_유지된다, WithdrawApiTest#탈퇴_직후_기존_액세스_토큰은_401이다 | 충족 | |
| PRO-03 | 마지막 매니저의 특수 처리는 후속 범위로 남긴다. | AccountService.java:releaseActive (TODO U-02) | - | 범위외 | 범위 제외. 특수 처리 없음 확인 |
| PRO-03 | 소속 해제와 요청 정리는 함께 확정한다. | AccountService.java:leave·withdraw (@Transactional 안에서 cleanupOnMembershipLeave) | AccountRollbackTest#요청_정리에서_예외가_나면_나가기가_롤백된다, #요청_정리에서_예외가_나면_탈퇴가_롤백된다 | 충족 | 원자성은 충족. 실제 정리는 아래 행 |
| PRO-03 | 떠나는 사람이 요청자면 CANCELLED(REQUESTER_LEFT), 승인 대기 후보면 WITHDRAWN(CANDIDATE_LEFT), 미수락 지정 대상이면 그 대상만 응답 불가, 응답 가능 대상 0명이면 CANCELLED(NO_ACTIVE_TARGET). | A: cleanupOnMembershipLeave 호출 / B: SubstituteRequestLifecycleService.java:cleanupOnMembershipLeave **빈 구현** | B 도메인 SubstituteRequestTest(상태 전이만). A 쪽 연동 테스트 없음 | 부분 | B 구현 대기. 지금은 나가도 진행 요청이 그대로 남는다. 상태 전이 규칙 자체는 B 소관 |
| PRO-03 | 종료 요청은 다시 열지 않는다. | B 소관 | - | 범위외 | B 담당 |
| PRO-03 | 승인·수락과 동시에 발생하면 먼저 확정된 최신 상태를 검증하여 한 번만 처리한다. | MembershipRepository.java:findActiveByStoreAndUserForUpdate·findAllByUserIdForUpdate (B의 lockActiveById와 같은 행 잠금) | StoreLeaveConcurrencyTest#승인이_먼저_소속을_잠그고_근무를_넘기면_나가기는_기다렸다가_409, #나가기가_먼저_잠그고_확정하면_승인_쪽_lockActiveById는_비활성으로_본다 | 충족 | |
| PRO-03 | (완료) 확인 취소는 소속·계정을 유지한다. | - | - | 범위외 | 프론트 |
| PRO-03 | (완료) 소속 해제 이후 이전 매장에 접근할 수 없다. | MembershipGuard.java:requireActive (ACTIVE만) | StoreLeaveApiTest#나간_크루원은_ST_03으로_가고_이전_매장_접근이_막히며_재참여하면_새_소속이_생긴다 | 충족 | |
| PRO-03 | (완료) 탈퇴 후 일반 서비스 회원정보의 추가 보존기간은 0일이다. | AccountService.java:withdraw (요청 트랜잭션 안에서 즉시 삭제) | WithdrawApiTest#과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다 | 충족 | |
| PRO-03 | (완료) 계정 접근·세션을 즉시 종료한다. | AccountService.java:withdraw (auth_sessions 삭제), SessionCheckingJwtConverter.java:convert | WithdrawApiTest#탈퇴_직후_기존_액세스_토큰은_401이다, AccessTokenAuthenticationTest#탈퇴한_계정의_토큰은_401 | 충족 | |
| PRO-03 | (완료) 이름·이메일·소셜 식별자·인증 토큰·회원/소속 연결은 지체 없이 파기한다. | User.java:withdraw (display_name·selected_role null), social_accounts·auth_sessions·user_consents·idempotency_records 삭제, Membership.java:withdraw (user_id null) | WithdrawApiTest#과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다, #나갔다가_다른_매장에_참여한_뒤_탈퇴하면_모든_소속의_연결이_끊긴다, #같은_소셜_계정으로_다시_로그인하면_새_회원이다 | 충족 | 이메일은 원래 저장하지 않음. 토큰 원문도 저장하지 않음 |
| PRO-03 | (완료) 본인 수신 알림은 지체 없이 파기한다. | A: AccountService.java:withdraw (anonymizeFor 호출) / B: anonymizeFor **빈 구현** | - | 부분 | B 구현 대기. 테스트 없음 |
| PRO-03 | (완료) 근무·요청·처리 기록에도 이름과 개인 연결 ID를 남기지 않는다. | 이름: 기록에 이름 없음(shift_events 값은 membership_id만). 연결: memberships.user_id null. 남는 것: memberships 행(id·role·personal_color·joined_at·left_at), shifts.assignee_membership_id, shift_events.actor_membership_id, 요청의 membership_id, users 톰스톤 행(id·withdrawn_at) | WithdrawApiTest#과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다 | 부분 | user_id 연결은 끊었지만 membership_id가 남는다. membership_id를 "개인 연결 ID"로 볼지 결정 필요(backend-split.md에도 "A 확인 필요"로 남아 있음) |
| PRO-03 | (완료) 다른 구성원 기록의 표시가 필요한 경우에만 "탈퇴한 구성원"으로 표시한다. | MembershipGuard.java:displayNameOf, ShiftQueryService.java:toItem (회색 #B0B4BA) | WithdrawApiTest#다른_구성원이_보는_과거_근무_담당자는_탈퇴한_구성원으로_표시된다, ShiftQueryApiTest#탈퇴한_담당자는_탈퇴한_구성원_회색 | 충족 | |
| PRO-03 | (완료) 다른 정보와 결합하여 재식별 가능하면 해당 개인 관련 상세·기록을 삭제한다. 이름만 바꾼 자료를 익명정보로 간주하지 않는다. | 없음 | - | 누락 | 재식별 가능 판단 기준과 삭제 대상이 정해지지 않았다. 예: 크루 2명 매장에서 "탈퇴한 구성원"의 근무 시간은 남은 사람이 누구인지 알 수 있다. 결정 필요 |
| PRO-03 | (완료) 운영자 접속기록은 1년 이상 별도 보관하며 일반 앱 요청·알림을 그 기록으로 취급하지 않는다. | 운영자 개인정보처리시스템 없음 | - | 범위외 | 인프라·운영 |
| PRO-03 | (완료) 그 밖의 법정 보존 의무 항목은 근거·기간을 명시해 분리 보관한다. | 확인된 항목 없음 | - | 범위외 | 확인된 법정 보존 항목이 생기면 반영 |
| PRO-03 | (완료) 캐시·백업에서도 파기하고 복원 시 재유입을 막는다. | 없음 | - | 누락 | 서버 캐시는 없다. 백업에서 복원할 때 탈퇴 계정을 다시 지우는 장치(탈퇴 목록 보관 등)가 없다. 인프라 담당 여부 포함 결정 필요 |

---

## 4. 관련 정책(P·B·D) 대조

| 정책 ID | 명세 문장(요약) | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| P01 | 매니저와 크루원의 화면·권한을 분리한다. | MembershipGuard.java:requireManager, ShiftActions.java:of | StoreApiTest#크루원은_초대_코드를_볼_수_없다, ShiftBatchApiTest#크루원은_미리보기도_등록도_403 | 충족 | |
| P02 | 매니저만 근무 추가·반복 등록·단건 삭제. 근무 수정 제외. | ShiftBatchService.java:requireManager, ShiftCommandService.java:delete | ShiftBatchApiTest#크루원은_미리보기도_등록도_403, ShiftDeleteApiTest#크루원은_본인_근무도_삭제할_수_없다 | 충족 | 수정 API 없음 확인 |
| P03 | 매장 크루원 모두 같은 매장의 시간표를 볼 수 있다. | ShiftQueryService.java:getMonth·getDay (requireActive, 역할 무관) | ShiftQueryApiTest#다른_매장_구성원은_목록을_볼_수_없다 | 충족 | |
| P04 | 내 근무는 본인 배정만, 월을 따라 갱신, 날짜 선택으로 축소하지 않는다. | ShiftQueryService.java:getMonth (MINE) | ShiftQueryApiTest#mine은_월_전체_내_근무만_시작순이고_all은_매장_기준_인원에_목록은_빈_배열 | 충족 | |
| P05 | 입력한 풀네임을 화면에서 일관되게 사용한다. | users.display_name 단일 원천 | COM-03 행 참고 | 충족 | |
| P11 | 여러 구성원에게 같은 요일·시간·기간의 정기 근무를 한 번에 등록한다. | ShiftPlanCalculator.java:plan | ShiftPlanCalculatorTest#apiExample | 충족 | |
| P12 | 달력 칸은 인원수, 선택일 목록·상세는 풀네임·개인 색상·시간을 시간순으로. | ShiftQueryService.java:dayCounts·getDay | ShiftQueryApiTest#같은_사람_같은_날_2건은_인원_1명_건수_2건, #동시각_근무는_한국어_이름순_같으면_membership_id순 | 충족 | |
| P13 | 예시 매장의 15명은 시안 데이터이며 상한·하드코딩이 아니다. | 구성원 수 상한 없음 | StoreMembersApiTest(member_count 5) | 충족 | |
| P14 | 로그인 없는 체험·예시 계정 전환·코드 없이 참여 제외. | 구현 없음 | ProdProfileSecurityTest#X_Test_User_Id_헤더로는_인증되지_않는다 | 범위외 | 범위 제외 확인. X-Test-User-Id는 local/test 프로필만 |
| B01 | 모든 조회/변경에서 인증 사용자·활성 소속·역할을 서버에서 검증하고 ID로 판정한다. | MembershipGuard.java (권한 근거는 DB 활성 소속), 요청 본문 store_id는 대상 지정만 | ShiftBatchApiTest#다른_매장_매니저는_본문_store_id로_대상을_지정해도_403, MembershipGuardTest 전체 | 충족 | |
| B02 | URL이나 ID를 바꿔도 다른 매장의 시간표에 접근하지 못한다. | MembershipGuard.java:requireActive | ShiftQueryApiTest#다른_매장_근무_상세는_403, StoreApiTest#다른_매장의_정보_코드_구성원은_403, ShiftDeleteApiTest#다른_매장_매니저는_403이고_상태를_알려주지_않는다 | 충족 | |
| B03 | 근무 저장 성공 시 공유 근무표에 반영하며 게시/배정 수락 단계는 없다. | ShiftBatchService.java:create | ShiftBatchApiTest#문서_예시_28건_중_2건_중복이면_26건만_등록되고_월_조회에_바로_보인다 | 충족 | |
| B04 | 반복 등록은 날짜별 독립 근무, 한 날짜 삭제는 전파하지 않음, 달력상 1개월. | ShiftPlanCalculator.java:validateRepeat, ShiftCommandService.java:delete | ShiftPlanCalculatorTest#maxEndDate_normalMonth, #maxEndDate_monthEnd, ShiftDeleteApiTest#반복_등록한_근무_중_하루를_지워도_다른_날짜는_남는다 | 충족 | |
| B05 | 매장·담당자·날짜·시작·종료가 모두 같은 근무는 새로 생성하지 않는다. 일부 겹침은 차단하지 않는다. | DB shifts_identical_live_uk, ShiftBatchService.java:existingKeys | ShiftRepositoryTest#같은_매장_담당자_시간의_유효_근무는_중복_저장되지_않는다 | 충족 | starts_at이 날짜를 포함하므로 정책 정의와 같다. 일부 겹침 허용은 테스트 없음 |
| B06 | 미리보기는 쓰기 없음, 최종 저장은 전체 성공/실패, 재시도에도 중복 없음. | ShiftBatchService.java:preview·create, IdempotencyService | ShiftBatchApiTest#미리보기는_아무것도_쓰지_않는다, #같은_키로_두_번_보내면_처음_결과_그대로이고_52건이_되지_않는다 | 충족 | |
| B17 | 근무 삭제 시 진행 요청도 CANCELLED_SOURCE_CHANGED로 종료하고 삭제 사유와 결과를 함께 기록한다. | A: ShiftCommandService.java:delete / B: closeBySourceDeleted 빈 구현 | ShiftDeleteRollbackTest#요청_종료에서_예외가_나면_근무는_삭제되지_않고_기록도_없다 | 부분 | SCH-07 행 참고 |
| B20 | 처리된 행동·오래된 버전·중복 제출은 중복 변경하지 않는다. | expected_version(삭제), Idempotency-Key(매장 생성·일괄 등록), PREVIEW_STALE | ShiftDeleteApiTest#오래된_expected_version이면_409_CHANGED이고_근무는_그대로, StoreApiTest#같은_키_재요청은_처음_결과_그대로_매장_1개 | 충족 | |
| B21 | 계정 탈퇴 시 개인 관련 기록은 개인정보 정책에 따라 파기·익명 처리한다. | AccountService.java:withdraw | WithdrawApiTest#과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다 | 부분 | PRO-03의 membership_id 잔존·재식별 기준·알림 파기 행 참고 |
| B22 | 매장 시간대 Asia/Seoul, 인원수는 사람 중복 제거, 건수는 근무별, 요일 1=월~7=일. | ShiftQueryService.STORE_ZONE, dayCounts, ShiftPlanCalculator.java:validateWeekdays | ShiftQueryApiTest#같은_사람_같은_날_2건은_인원_1명_건수_2건, ShiftPlanCalculatorTest#invalidWeekdays | 충족 | |
| D01 | 달력 칸은 날짜별 인원수, 같은 사람 여러 근무는 1명. | ShiftQueryService.java:dayCounts | ShiftQueryApiTest#같은_사람_같은_날_2건은_인원_1명_건수_2건 | 충족 | |
| D02 | Google·Apple 로그인과 가입 순서 유지, 소속 없는 계정 복귀 규칙. | COM-01·COM-02 행 참고 | OnboardingFlowApiTest | 충족 | |
| D03 | 근무 0분 초과·24시간 미만, 자정 넘김은 익일 종료 한 건, 반복 달력상 1개월. | ShiftPlanCalculator.java:validateTimes·validateRepeat | ShiftPlanCalculatorTest#lengthBounds, #overnightRepeat, #maxEndDate_monthEnd | 충족 | |
| D04 | 유효 코드면 즉시 참여, 매장 공통 8자리 한 개, 매장 종료 전까지 유효, 재발급 UI 제외. | StoreCommandService.java:join, InviteCode | MembershipJoinApiTest#크루원_가입_코드_참여_후_next_screen은_CA01 | 충족 | 매장 종료 기능 자체가 없음(StoreState ACTIVE만) |
| D05 | 탈퇴 후 보존 0일, 세션 즉시 종료, 식별정보·연결·수신 알림 파기, 기록에 이름·개인 연결 ID 없음, 재식별 시 삭제, 백업 파기. | AccountService.java:withdraw | WithdrawApiTest 전체 | 부분 | PRO-03 행 참고(알림·membership_id·재식별·백업) |
| D08 | 여러 직원에게 같은 패턴 일괄 적용, 직원별 다른 패턴 동시 등록 제외. | ShiftBatchInput (공통 패턴 하나) | ShiftPlanCalculatorTest#noRepeat_threeAssignees | 충족 | |
| D09 | 근무 배정 대상 매니저는 허용한다(대타 요청·후보는 제외). | ShiftBatchService.java:requireAllActive, StoreQueryService.java:getMembers (SUBSTITUTE_CANDIDATES는 매니저 제외) | ShiftBatchApiTest#매니저_본인도_근무자로_등록할_수_있다, StoreMembersApiTest#substitute_candidates는_본인과_매니저_제외_크루만 | 충족 | 요청·후보 검증 자체는 B |
| D10 | 매니저만 근무 추가·단건 삭제, 근무 수정·AP-01 제외. | P02와 같음 | P02와 같음 | 충족 | |
| D11 | 매니저는 과거 근무도 한 건 삭제할 수 있다. | ShiftCommandService.java:delete | ShiftDeleteApiTest#과거_근무도_삭제할_수_있다 | 충족 | |
| D14 | 이름 2~15자·매장명 1~15자(공백 제거), 코드 대문자·숫자 8자리, 근무 0분 초과·24시간 미만, 반복 1개월·요일 1개 이상. | DisplayName, StoreName, StoreCommandService.normalizeCode, ShiftPlanCalculator | DisplayNameTest, StoreNameTest, MembershipJoinApiTest#형식_오류는_422_VALIDATION_FAILED_소문자도_거부, ShiftPlanCalculatorTest | 충족 | 대타 대상 검증은 B |
| D15 | 내 근무 목록은 표시된 월, 날짜 선택은 CA-03으로 연결하며 월 목록을 하루로 바꾸지 않는다. | ShiftQueryController.java:list (month/date 분리) | ShiftQueryApiTest#날짜_조회는_scope를_무시하고_삭제된_근무를_뺀다 | 충족 | |
| D20 | 나가기·탈퇴 직전 미종료 근무 재확인, 소속 해제와 요청 정리 함께 확정, 동시 처리 한 번만. | AccountService.java:leave·withdraw·releaseActive | StoreLeaveApiTest, WithdrawApiTest, AccountRollbackTest, StoreLeaveConcurrencyTest | 부분 | A 쪽은 충족. 요청 정리(B 빈 구현) 대기 |
| D21 | 거절 사유 입력 없음, 내/전체 탭 선택과 스와이프. | - | - | 범위외 | 프론트·B |
| 행동표 | 초대 코드 확인·복사: 매니저 가능, 크루원 불가. | StoreQueryService.java:getInviteCode | StoreApiTest#크루원은_초대_코드를_볼_수_없다 | 충족 | |
| 행동표 | 근무 변경 이력 메뉴: 양쪽 불가. | 조회 API 없음 | - | 범위외 | 범위 제외 확인 |
| 8절 | 매장 나가기: 이전 매장 근무표·요청·구성원 접근 차단, 재참여는 새 소속, 과거 소속 자동 복구 없음. | Membership.java:leave, StoreCommandService.java:join (새 행 생성) | StoreLeaveApiTest#나간_크루원은_ST_03으로_가고_이전_매장_접근이_막히며_재참여하면_새_소속이_생긴다, MembershipRepositoryTest#나간_뒤에는_같은_매장에_다시_참여할_수_있다 | 충족 | |
| 8절 | 매장 나가기는 기존 요청·근무 기록을 자동 삭제하지 않는다. 탈퇴한 계정으로 접근을 허용하지 않는다. | AccountService.java:leave (근무 미변경), SessionCheckingJwtConverter | AccessTokenAuthenticationTest#탈퇴한_계정의_토큰은_401 | 충족 | 나가기 후 과거 근무가 남는지 직접 확인하는 단정은 없음 |
| 8절 | 약관: 필수 미동의는 가입 차단, 선택 미동의는 가입 가능. | ConsentService.java:validate | ConsentApiTest#필수_미동의는_422이고_해당_문서가_field_errors에_있다 | 부분 | 운영 프로필 약관 0건 문제(COM-03 행 참고) |

---

## 5. 정책 10절 개발 검수 예시

| 기능 ID | 명세 문장(사례 → 기대 결과) | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| 10절-1 | 소셜 로그인 후 이름·역할까지만 저장하고 앱 종료 → 다음 로그인은 ST-01/ST-03으로 복귀, 매장 없이 근무표 조회 불가. | NextScreen.java:of, MembershipGuard.java:requireActive | OnboardingFlowApiTest#정책10절_이름_역할까지만_저장한_계정은_ST01_ST03으로_복귀하고_매장_데이터는_403, #정책10절_이름_역할까지만_저장한_계정의_근무표_조회는_403 | 충족 | |
| 10절-2 | 2027-01-31 시작 반복 등록 → 종료일 상한 2027-02-28, 3월 날짜는 등록 불가. | ShiftPlanCalculator.java:validateRepeat | ShiftPlanCalculatorTest#maxEndDate_monthEnd | 충족 | 단위 테스트만 있음(API 테스트 없음) |
| 10절-3 | 알림 27개 중 첫 20개만 표시하고 진입 성공 → 27개 모두 읽음. | - | - | 범위외 | B 담당 |
| 10절-4 | 승인 대기 알림을 읽고 뒤로가기 → 표시점은 사라져도 승인 대기 유지. | - | - | 범위외 | B 담당 |
| 10절-5 | 알림 생성 후 정확히 7일 경과 → 조회/표시점 제외·자동 삭제, 요청·승인 대기 유지. | A 관련분: SH-02 승인 요청 보기는 알림과 무관(ShiftActions) | - | 범위외 | B 담당. A 관련분은 SCH-02 "알림 7일 만료 후에도" 행(테스트 없음) |
| 10절-6 | 최종 승인 처리 → 요청자·수락 후보·처리 매니저에게 결과 알림, 중복 없음. | 알림은 B. A 몫인 `StoreManagerQuery`는 store/application/MembershipGuard.java:findActiveManagers | MembershipGuardTest#주입되는_StoreManagerQuery는_A의_실제_구현이다, #소속_매니저는_그_매장_활성_매니저만_id순으로_돌려준다, #매니저가_없는_매장이면_빈_목록 | 충족 | [수정] A 구현 추가로 B 스텁은 자동으로 빠짐. 결과 알림 자체는 B 담당 |
| 10절-7 | 만료 또는 근무 삭제 → 요청 결과만 종료 상태로 갱신, 새 알림 없음. | A: ShiftCommandService.java:delete / B: closeBySourceDeleted 빈 구현 | - | 부분 | 근무 삭제 쪽은 B 구현 대기. 만료는 B 담당. 테스트 없음 |
| 10절-8 | 후보가 매장을 나가려는데 다른 예정 근무가 있음 → 정리 안내, 나가기·후보 철회 정리 모두 미확정. | AccountService.java:releaseActive (근무 검사가 요청 정리보다 먼저) | AccountServiceTest#끝나지_않은_근무가_있으면_FUTURE_SHIFT_REMAINS이고_아무것도_바꾸지_않는다, StoreLeaveApiTest#내일_근무가_남은_크루원은_나갈_수_없고_아무것도_바뀌지_않는다 | 충족 | 실제 "후보" 상태 요청을 만든 테스트는 없음(일반 크루원으로 검증) |
| 10절-9 | 예정 근무 없는 후보 소속 해제 성공 → WITHDRAWN/CANDIDATE_LEFT로 요청 종료, 남은 유효 관계자만 결과 알림. | A: cleanupOnMembershipLeave 호출 / B: 빈 구현 | - | 부분 | B 구현 대기. A·B 연동 API 테스트 없음 |
| 10절-10 | 계정 탈퇴 → 토큰·식별정보·개인 연결을 파기, 이름만 바꾸고 식별 가능한 기록을 남기지 않음. | AccountService.java:withdraw | WithdrawApiTest#과거_근무만_있으면_탈퇴되고_개인정보와_연결이_모두_파기된다 | 부분 | membership_id 잔존·재식별 기준 미정(PRO-03 행 참고) |

---

## 6. 범위 제외 항목 실수 구현 점검

| 항목 | 점검 방법 | 결과 |
| --- | --- | --- |
| 근무 수정(SCH-06) | `@PatchMapping`·`@PutMapping` /shifts 검색, Shift 엔티티 수정 메서드 | 없음. starts_at·ends_at은 `updatable=false`. `Shift.reassign`은 대타 승인 전용(ShiftAssignmentService.reassign)이라 수정 기능 아님 |
| 근무 변경 이력 조회(PRO-02) | shift_events 조회 API·Repository 조회 메서드 | 없음. shift_events는 쓰기만 함(서버 처리 기록, 명세상 유지 대상) |
| 초대코드 재발급(STR-02 일부) | 재발급 API·서비스 호출 | API 없음. **`store/domain/InviteCode.java:invalidate`가 main·test 어디서도 쓰이지 않는 메서드로 남아 있음**(노출 경로는 없음) |
| 외부 공유 링크(SUB-03) | share·link 검색 | 없음 |
| 푸시 | push·device token 검색 | 없음 |
| 로그인 없는 체험(DEMO-01) | 인증 예외 경로 | 없음. 인증 없이 열린 경로는 소셜 로그인·문서 경로뿐. X-Test-User-Id는 local/test 프로필만(ProdProfileSecurityTest) |
| 매니저의 대타 요청·후보 참여 | A 쪽 노출 경로 | 근무 상세 행동에 매니저 요청 없음, 대타 후보 목록에서 매니저 제외 |
| 마지막 매니저 특수 처리 | AccountService | TODO 주석만 있고 처리 없음 |
| 이메일·카카오 로그인 | provider | google·apple만 |

---

## 7. 수정 계획 제안 (누락·부분 항목만)

A가 고칠 수 있는 것과 B·PM 결정이 필요한 것을 나눴다.

**7-1 처리 결과(2026-11-11 승인 후 반영)**
- 1 StoreManagerQuery: 완료. `MembershipGuard`가 구현, `MembershipRepository.findByStoreIdAndStateAndRoleOrderByIdAsc` 추가, 테스트 3건
- 2 409 남은 근무 정보: 코드 변경 없음. ErrorResponse가 B 소유라 아래 제안만 남긴다. 프론트 합의 전까지는 프론트가 `scope=mine` 월 조회로 안내
  - B 제안: FUTURE_SHIFT_REMAINS일 때 `current_state`와 같은 방식의 선택 필드(예: `remaining_shift_count`, `next_shift_starts_at`)를 추가할 수 있게 ErrorResponse 확장 여부 검토
- 3 테스트 보강: 3건 추가(초대 코드 기간, 빈 값·형식 오류 문구, 알림 만료 후 SH-02 승인 진입). 나머지 2건(일부 겹침, 구성원 목록 필드)은 기존 테스트가 이미 확인하고 있었다
- 4 `InviteCode.invalidate()`: 삭제. 함께 쓰이지 않던 `InviteCode.isValid()`도 삭제(추가 지시)

### 7-1. A가 바로 할 수 있는 것

| 순서 | 대상 | 할 일 | 파일 | 테스트 |
| --- | --- | --- | --- | --- |
| 1 | StoreManagerQuery A 구현(10절-6) | `MembershipGuard`가 `StoreManagerQuery`를 구현하게 하거나 별도 `@Service` 추가. ACTIVE MANAGER를 id 오름차순으로 반환, 잠금 없음. 추가되면 B 스텁은 자동으로 빠진다 | store/application/MembershipGuard.java (또는 새 StoreManagerQueryService.java) | MembershipGuardTest에 "매장의 활성 매니저만 id순", "나간·탈퇴 매니저 제외", "주입되는 구현이 A 구현" 3건 |
| 2 | 나가기·탈퇴 409 응답의 남은 근무 정보(PRO-03 "부분") | 프론트와 합의 후, 필요하면 FUTURE_SHIFT_REMAINS 응답에 남은 근무 건수나 가장 가까운 근무 시각을 붙인다. ErrorResponse는 B 소유(common)라 필드 추가는 **B에 제안**만 한다. 대안: 응답은 그대로 두고 프론트가 scope=mine 월 조회로 안내 | (합의 결과에 따라) AccountService.java, common 변경 제안서 | StoreLeaveApiTest·WithdrawApiTest의 409 응답 본문 단정 추가 |
| 3 | 빠진 테스트 보강("테스트 없음" 항목) | 일부만 겹치는 근무가 등록되는지(B05), 알림과 무관하게 SH-02 승인 요청 보기가 유지되는지, 초대 코드가 시간이 지나도 유효한지, 구성원 목록 응답에 식별자가 없는지, 빈 코드와 형식 오류 메시지가 다른지 | ShiftBatchApiTest, ShiftQueryApiTest, MembershipJoinApiTest, StoreMembersApiTest | 5건 내외 |
| 4 | 미사용 `InviteCode.invalidate()` | 재발급 범위 제외에 맞춰 삭제할지, 매장 종료 대비로 둘지 결정 후 처리(삭제 시 테스트 픽스처 영향 없음 확인함) | store/domain/InviteCode.java | 없음 |

### 7-2. B 구현이 필요한 것 (A는 연동 테스트만 추가)

| 대상 | B에 요청할 내용 | A가 추가할 테스트(B 구현 후) |
| --- | --- | --- |
| 근무 삭제 시 요청 종료(SCH-07, B17, 10절-7) | `closeBySourceDeleted` 구현: 진행 요청을 CANCELLED_SOURCE_CHANGED/SHIFT_DELETED로 종료, 이벤트 기록, 알림 없음, 종료한 id 반환 | ShiftDeleteApiTest: 진행 요청이 있는 근무 삭제 → closed_request_id 반환·요청 상태 확인·알림 0건 |
| 나가기·탈퇴 요청 정리(PRO-03, D20, 10절-9) | `cleanupOnMembershipLeave` 구현: REQUESTER_LEFT / CANDIDATE_LEFT / UNAVAILABLE / NO_ACTIVE_TARGET | StoreLeaveApiTest: 요청자·후보·미수락 대상 각각 나가기 → 요청 상태, 남은 관계자만 알림 |
| 탈퇴 시 수신 알림 파기(PRO-03, D05) | `anonymizeFor` 구현: 본인 수신 알림 삭제, 요청·이벤트 스냅샷의 개인 정보 제거 | WithdrawApiTest: 탈퇴 후 그 소속이 받은 알림 0건 |

### 7-3. 결정 필요 (임의로 정하지 않음)

| 항목 | 질문 | 관련 |
| --- | --- | --- |
| 운영 약관 0건 | 운영 배포 전 약관 문서를 마이그레이션으로 넣을지, 또는 유효 문서가 0건이면 동의 저장을 막을지(예: 500/503). 현재는 빈 목록으로 필수 동의 없이 통과 | COM-03, 8절 약관, U-04 |
| membership_id 잔존 | 탈퇴 후 shifts·shift_events·요청에 남는 membership_id(및 WITHDRAWN 소속 행의 personal_color·joined_at)를 "개인 연결 ID"로 볼지. 본다면 탈퇴 전용 대체 소속 행으로 바꿔 끼우는 등 방식 결정 필요 | PRO-03, D05, B21, backend-split "A 확인 필요" |
| 재식별 가능 기록 삭제 기준 | "다른 정보와 결합하여 재식별 가능하면 삭제"의 판정 기준(예: 매장 구성원 수 N명 이하면 탈퇴자 근무 삭제?)과 삭제 대상(과거 근무 행 자체인지) | PRO-03, D05 |
| 백업 복원 시 재유입 차단 | 탈퇴 사용자 id 목록을 남겨 복원 후 재파기할지, 인프라 담당으로 넘길지 | PRO-03, D05 |
| 내 탭 달력 인원 의미 | scope=mine일 때 달력 칸 숫자를 내 근무(0/1)로 둘지 매장 인원으로 둘지 | SCH-01 |

### 7-4. 이번 검토에서 임의로 정한 것
- 프론트 화면 동작만 있는 문장은 "범위외(프론트)"로 판정했다(사용자 지정 판정값 4개에 맞추기 위함)
- B 빈 구현 때문에 결과가 안 나오는 항목은 A 호출이 맞더라도 "부분"으로 판정했다
