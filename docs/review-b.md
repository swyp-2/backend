# 개발자 B 담당 기능 명세 대조 (review-b)

- 기준 문서: `docs/md/02 · 기능명세서.md`(v3.2), `docs/md/03 · 서비스 정책.md`(v2.0)
- 대상 코드: 최초 검토는 브랜치 `chore/remove-integration-stubs`(커밋 2638020, 2026-11-12)에서 코드 수정 없이 했다
- **2026-11-12 반영**: 브랜치 `fix/manager-lock-and-temp-decisions`에서 결정 필요 3건을 처리했다(아래 "결정 결과"). 반영된 행은 비고에 "[반영]"으로 표시했다
- 범위: SUB-01·02·04~10, APR-01~02, NTF-01~05 + 관련 정책 P06~P10, B07~B21, D05·D09·D12·D16~D20, N01~N08, 5~7절, 10절 예시
- 검토 시점 `./gradlew test`: 541건 중 실패 0, 건너뜀 1 (BUILD SUCCESSFUL). 반영 후 542건 중 실패 0, 건너뜀 1. 아래 "검증 테스트"는 모두 통과했다
- 판정 기준과 표기는 `docs/review-a.md`와 같다(충족 / 부분 / 누락 / 범위외). 이번에는 **변경**을 더 둔다:
  팀 결정으로 명세와 다르게 구현했고, 그 결정이 `docs/backend-split.md`에 기록돼 있다는 뜻이다(PM 문서 반영 필요)
- 테스트 이름은 `클래스#메서드`로 줄여 적는다. 자주 쓰는 클래스는 다음처럼 줄인다
  - Create = SubstituteRequestCreateApiTest, Query = SubstituteRequestQueryApiTest,
    Tr = SubstituteRequestTransitionApiTest, TrCon = SubstituteRequestTransitionConcurrencyTest,
    TrExp = SubstituteRequestTransitionExpiryApiTest, Life = SubstituteRequestLifecycleIntegrationTest,
    Noti = NotificationApiTest, Req = SubstituteRequestTest(도메인)

## 요약

| 구분 | 건수 | 내용 |
| --- | --- | --- |
| 결정 필요 | 0 (최초 3) | [반영] 3건 모두 처리. 2건은 임시 결정(PM 확인 필요), 1건은 코드 수정 |
| 변경(임시 결정) | 2 | PM 부재 중 B가 정함: (1) 탈퇴 후 요청·진행 기록의 membership_id 유지(D05) (2) 소속 해제로 거절한 대상만 남으면 ALL_DECLINED(D20) |
| 변경(팀 결정) | 3 | 알림 목록 NT-01·`GET /me/notifications` 제외, 읽음을 요청 탭 점·카드 점 두 단계로 변경, 결과 알림에서 행위자 본인 제외 |
| 부분 | 0 (최초 1) | [반영] APR-01 매니저 소속 잠금 추가 |
| 누락 | 0 | 팀 결정으로 뺀 알림 목록은 "변경"으로 센다 |
| 테스트 없음(충족) | 2 | 상태 변경으로 목록 정렬이 바뀌지 않음(정렬 키 구조로 보장), 철회 결과 알림의 요청자·매니저 수신(알림 종류만 확인) |

---

## 1. 크루원 대타 요청

### SUB-01 내 근무에서 대타 동료 선택

| 명세 문장 | 구현 위치(파일:메서드) | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 시작 전 본인 근무에서 같은 매장 크루원 한 명 이상을 지정한다. | SubstituteRequestCommandService:create·validateTargets | Create#요청을_만들면_WAITING_상세와_대상자_알림이_생긴다, #본인_담당이_아닌_근무는_403, #이미_시작한_근무는_409_REQUEST_EXPIRED | 충족 | |
| 본인·매니저·퇴사자·다른 매장 구성원은 후보에서 제외한다. | CommandService:validateTargets(findCrewCandidates)·lockMemberships | Create#본인을_대상으로_보내면_422, #매니저를_대상으로_보내면_422, #다른_매장_구성원을_대상으로_보내면_422 | 충족 | 동료 목록 화면 API는 A(GET /stores/{id}/members) |
| 0명일 때 전송을 막는다. | CommandService:validateTargets | Create#대상이_없으면_422 | 충족 | |
| 이미 진행 요청이 있으면 새로 만들지 않고 해당 상세로 연결한다. | CommandService:create(existing_request_id) | Create#진행_중_요청이_있으면_409와_기존_요청_id | 충족 | |
| 날짜·시간·담당자는 읽기 전용, 카드·인원 표시 | - | - | 범위외 | 프론트 |

### SUB-02 대타 요청 전송

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 요청과 지정 대상을 저장해 대상자의 받은 요청과 새 알림을 생성한다. 외부 링크·푸시는 만들지 않는다. | CommandService:create → NotificationWriter:notify | Create#요청을_만들면_WAITING_상세와_대상자_알림이_생긴다 | 충족 | |
| 근무당 WAITING/PENDING_MANAGER 요청은 한 건만 허용한다. | 부분 유니크 substitute_requests_shift_active_uk + 근무 행 잠금 | SubstituteSchemaTest#같은_근무에_진행_중_요청은_한_건만_저장된다, SubstituteRequestCreateConcurrencyTest#같은_근무에_동시에_요청하면_한_건만_성공한다 | 충족 | |
| 본인 담당·시작 전·소속·대상 역할을 서버에서 검증한다. | CommandService:create | Create#본인_담당이_아닌_근무는_403, #이미_시작한_근무는_409_REQUEST_EXPIRED, #삭제된_근무는_409_SHIFT_STATE_CONFLICT, #다른_매장_사용자는_403 | 충족 | |
| 재시도 중복을 방지한다. | Controller:create(IdempotencyService) | Create#같은_키로_다시_보내면_처음_결과를_그대로_돌려준다, #같은_키에_다른_내용이면_422, #키가_없으면_422 | 충족 | |
| 매니저 요청 생성과 후보 지정을 서버에서도 차단한다. | CommandService:create(CREW 확인)·validateTargets | Create#매니저는_요청할_수_없다, #매니저를_대상으로_보내면_422 | 충족 | |
| 실패 시 선택값 유지 | - | Create#실패한_요청은_아무것도_남기지_않는다 | 범위외 | 선택값 유지는 프론트. 서버는 실패 시 아무것도 남기지 않음 |

### SUB-04 요청 상세 조회·접근 확인

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 크루원은 본인이 요청자 또는 지정 대상인 요청만, 매니저는 소속 매장 요청을 조회한다(B10). | QueryService:getDetail·relationOf | Query#같은_매장이라도_관계없는_크루원은_403, #다른_매장_사용자는_403, #매니저는_WAITING에서_행동_없이_전체_대상을_본다 | 충족 | |
| 상세 열기는 상태 변경이 아니다. | QueryService(readOnly) | - | 충족 | 구조로 보장. 카드 점 확인(check)은 알림만 바꾼다 |
| 관계와 최신 상태에 맞는 버튼만 표시한다. | AllowedActions:of | AllowedActionsTest 전체, Query#지정_대상은_수락_거절_행동과_본인_응답만_본다, #요청자는_취소_행동과_전체_대상을_본다 | 충족 | |
| 다른 사용자의 요청 정보를 우회 조회하지 못한다. 응답에 개인 식별 정보 없음 | QueryService:getDetail | Query#응답에_user_id나_이메일이_없다 | 충족 | 지정 대상·후보는 다른 대상의 응답을 보지 않는다 |

### SUB-05 보낸·받은 요청 목록·진행 기록

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 보낸 요청은 본인 생성, 받은 요청은 본인 지정 건으로 나눈다. | QueryService:list, SubstituteRequestRepository:findSent*·findReceived* | Query#보낸_요청과_받은_요청을_나눠서_최신순으로_보여준다 | 충족 | 현재 활성 소속 기준(이전 매장 요청 제외) |
| 생성 시각 내림차순, 같으면 ID 내림차순. 20개씩 커서(D17). | Repository(order by createdAt desc, id desc), RequestCursor | Query#커서로_20개씩_나눠_가져온다, RequestCursorTest 전체 | 충족 | |
| 상태 변경으로 정렬 위치를 바꾸지 않는다. | 정렬 키가 created_at·id뿐 | - | 충족 | **테스트 없음**(구조로 보장) |
| 진행 기록은 실제 요청·수락·거절·승인·취소·철회 사건을 보여준다. | QueryService:getDetail(events) | Query 상세 테스트들(events 포함) | 충족 | 만료·삭제·소속 해제 기록도 함께 나온다 |
| 0건·로딩·실패 구분 | - | - | 범위외 | 프론트 |

### SUB-06 대타 수락·재수락

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| WAITING의 첫 수락자 한 명을 후보로 확정하고 PENDING_MANAGER. 담당자는 유지. | TransitionService:accept, SubstituteRequest:accept | Tr#수락하면_후보가_확정되고_담당자는_그대로이며_수락한_본인을_뺀_요청자_매니저에게_알림, Req#첫_수락은_후보를_확정하고_승인_대기로_바꾼다 | 충족 | |
| 동시 수락은 한 명만 성공한다. | 요청 행 FOR UPDATE + @Version | TrCon#여러_명이_동시에_수락하면_한_명만_후보가_된다 | 충족 | |
| WAITING이면 거절했던 대상의 재수락 가능. ALL_DECLINED는 재개하지 않는다(D12). | RequestRecipient:canAccept | Tr#거절했던_대상도_WAITING이면_다시_수락할_수_있다, #모두_거절하면_ALL_DECLINED로_끝나고_다시_수락할_수_없다 | 충족 | |
| 다른 후보 확정·시작·종료 뒤에는 차단하고 최신 상태를 안내한다. | requireOpen, conflict(current_state) | Tr#후보가_정해진_뒤의_수락은_409와_최신_상태, TrExp#시작_시각이_지나면_수락_거절은_REQUEST_EXPIRED | 충족 | |
| 매니저에게 승인 요청 알림을 생성한다. | TransitionService:record | Noti#첫_수락이_생기면_매니저에게도_탭_점이_켜진다 | 충족 | 수락자 본인 제외는 NTF-04 참고 |

### SUB-07 대타 요청 거절

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 확인 없이 본인 응답을 거절 처리하고, 다른 대상의 응답 기회를 유지한다. | TransitionService:reject | Tr#일부_거절은_WAITING을_유지하고_요청자와_매니저에게만_알림 | 충족 | 팝업 없음은 프론트 |
| 모든 지정 대상이 거절하면 ALL_DECLINED. | TransitionService:reject(미응답 없음 → closeAllDeclined) | Tr#모두_거절하면_ALL_DECLINED로_끝나고_다시_수락할_수_없다 | 충족 | |
| 후보 확정·종료 뒤의 거절은 막는다. 중복 실행 방지. | requireOpen, canDecline, 멱등성 키 | Tr#이미_거절한_대상의_재거절은_409, TrCon#마지막_거절과_수락이_동시에_오면_ALL_DECLINED와_PENDING_MANAGER_중_하나로만_끝난다 | 충족 | |

### SUB-08 보낸 요청 취소 / SUB-09 수락 철회

| 기능 ID | 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| SUB-08 | WAITING·PENDING_MANAGER에서 요청자가 취소하면 CANCELLED, 담당자 유지. | TransitionService:cancel | Tr#요청자는_승인_대기에서도_취소할_수_있고_직전_후보에게도_알림, Req#요청자_취소는_WAITING과_PENDING_MANAGER에서_가능 | 충족 | |
| SUB-08 | 최종 승인 뒤 취소 불가. 승인과 취소가 경합하면 먼저 확정된 처리만 유효. | requireOpen, 요청 행 잠금 | Tr#승인된_요청은_취소_철회_재승인할_수_없다, TrCon#승인과_취소가_동시에_오면_하나만_반영되고_담당자는_그_결과와_맞는다 | 충족 | |
| SUB-09 | 후보가 철회하면 WITHDRAWN으로 요청 전체 종료, 자동 재모집 없음. | TransitionService:withdraw | Tr#후보가_철회하면_WITHDRAWN으로_끝나고_자동_재모집하지_않는다, TrCon#승인과_철회가_동시에_오면_하나만_반영된다 | 충족 | 결과 알림은 종류만 확인하고 수신자 구성은 검사하지 않음(**수신자 테스트 없음**, TransitionResultRecipientsTest가 규칙 자체는 확인) |
| SUB-09 | 후보가 아닌 사용자는 철회할 수 없다. | TransitionService:withdraw | Tr#후보가_아니면_철회할_수_없다 | 충족 | |

### SUB-10 요청 만료·근무 삭제·소속 해제 종료

| 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- |
| 시작 시각에 도달하면 EXPIRED, 알림 없음(B15). | SubstituteRequestExpiryJob·ExpiryService | SubstituteRequestExpiryJobTest 전체 | 충족 | 매분 실행. 반영 전이라도 API가 REQUEST_EXPIRED로 막는다 |
| 시작 이후 수락·승인을 서버에서도 차단한다. | requireOpen | TrExp 전체 | 충족 | 취소·철회·최종 거절도 막는다(2026-11-10 결정) |
| 근무 삭제 시 CANCELLED_SOURCE_CHANGED + 삭제 사유, 알림 없음(B17). | LifecycleService:closeBySourceDeleted | Life#근무를_삭제하면_진행_중_요청이_삭제_사유로_종료되고_알림은_없다, #승인_대기_요청도_근무_삭제로_종료된다 | 충족 | 표기 결정은 backend-split.md(2026-11-09) |
| 요청자 소속 해제 → CANCELLED(REQUESTER_LEFT), 후보 → WITHDRAWN(CANDIDATE_LEFT). | LifecycleService:cleanupOnMembershipLeave | Life#요청자가_나가면_REQUESTER_LEFT로_취소되고_남은_관계자에게만_알린다, #승인_대기_후보가_나가면_CANDIDATE_LEFT로_철회된다 | 충족 | |
| 미수락 지정 대상이면 그 대상만 응답 불가, 나머지 유지. 응답 가능 대상 0명이면 CANCELLED(NO_ACTIVE_TARGET). | LifecycleService:cleanup | Life#지정_대상이_나가도_다른_미응답_대상이_있으면_요청은_계속되고_기록과_알림은_없다, #지정_대상이_모두_나가면_NO_ACTIVE_TARGET으로_취소된다 | 충족 | |
| (명세에 없는 경우) 마지막 미응답 대상이 나가고 거절한 대상만 남음 | LifecycleService:cleanup → closeAllDeclined | Life#마지막_미응답_대상이_나가고_거절한_대상만_남으면_ALL_DECLINED | **변경(임시)** | [반영] 2026-11-12 임시 결정: "응답 가능 대상" = 미응답 대상으로 해석해 ALL_DECLINED 유지. 근거는 backend-split.md "B 구현: SubstituteRequestLifecycle" 절 → PM 확인 |
| 종료 요청은 다시 열지 않는다. 승인·수락과 동시에 발생하면 한 번만 처리. | 요청 행 잠금 후 재확인 | Life#종료된_요청은_다시_열거나_바꾸지_않는다, #승인과_후보의_소속_해제가_동시에_일어나도_한_번만_처리된다, Req#종료된_요청은_어떤_행동으로도_덮어쓰지_않는다 | 충족 | |
| 소속 해제에 따른 취소·철회 결과는 유효한 관계자에게 전달(떠난 계정 제외). | LifecycleService:recipientsAfterLeave | Life#요청자가_나가면_REQUESTER_LEFT로_취소되고_남은_관계자에게만_알린다 | 충족 | |

## 2. 매니저 최종 승인

| 기능 ID | 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| APR-01 | 승인하면 담당자를 후보로 바꾸고 APPROVED·처리 기록·결과 알림을 함께 저장한다. | TransitionService:approve → ShiftAssignment:reassign | Tr#승인하면_그_근무만_담당자가_후보로_바뀌고_처리한_매니저를_뺀_관계자에게_알림 | 충족 | |
| APR-01 | PENDING_MANAGER·시작 전·원래 담당자·버전·후보 소속을 재검사한다(B16). | approve(activeParties), reassign(expectedVersion) | Tr#WAITING은_승인_최종거절할_수_없다, TrExp#시작_시각이_지나면_승인_최종거절은_REQUEST_EXPIRED이고_담당자는_그대로, Life#승인과_후보의_소속_해제가_동시에_일어나도_한_번만_처리된다, Tr#후보에게_완전히_같은_근무가_있으면_승인은_409_SHIFT_STATE_CONFLICT이고_아무것도_바뀌지_않는다 | 충족 | |
| APR-01 | 승인 주체는 소속 매니저(B01·B16) | TransitionService:lock(행위자 소속 lockActiveById) | Tr#크루원은_승인_최종거절할_수_없다, Life#매니저가_나가는_중이면_그_매니저의_승인은_기다렸다가_403이고_요청은_그대로다 | 충족 | [반영] 매니저 행위자도 소속을 잠가 나가기·탈퇴와 직렬화. 새 테스트는 수정 전 코드에서 실패하는 것을 확인함 |
| APR-01 | 부분 저장·중복 승인 방지 | 한 트랜잭션 + 멱등성 키 | Tr#같은_키로_다시_보내면_처음_결과를_그대로_돌려준다, #실패한_시도의_키로는_다시_시도할_수_있다 | 충족 | |
| APR-01 | 알림이 만료돼도 SH-02에서 승인 상세를 열 수 있다. | LifecycleService:findActiveByShift(A의 근무 상세가 호출) | ShiftDetailApprovalEntryTest#승인_요청_알림이_만료돼_지워져도_매니저_근무_상세에서_승인_요청_보기가_남는다 | 충족 | A 테스트 |
| APR-02 | PENDING_MANAGER에서 REJECTED, 담당자 유지, 처리자·시각 기록. 사유 입력 없음. | TransitionService:finalReject | Tr#최종_거절하면_REJECTED이고_담당자는_그대로 | 충족 | 본문 없는 API |
| APR-02 | 종료 요청·WAITING은 최종 거절 대상이 아니다. | SubstituteRequest:reject(requireStatus) | Tr#WAITING은_승인_최종거절할_수_없다, Req#매니저_최종_거절은_PENDING_MANAGER에서만 | 충족 | |

## 3. 앱 내부 알림

알림 목록 화면(NT-01)은 2026-11-11 팀 결정(Figma 기준)으로 만들지 않고, 빨간 점 두 단계로 바꿨다(`backend-split.md` "B API: 빨간 점").

| 기능 ID | 명세 문장 | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| NTF-01 | 알림 화면 진입이 성공하면 그 시점까지의 본인 알림을 읽음 처리, 이후 도착분은 미읽음. | NotificationService:open, Repository:markAllRead | Noti#새_요청을_받으면_탭_점이_켜지고_요청_탭에_들어가면_꺼진다, #요청_탭_진입_뒤_도착한_알림은_다시_점을_켠다, #요청_탭_진입은_여러_번_해도_같다 | **변경** | "알림 화면 진입" → "요청 탭 진입" |
| NTF-02 | 알림 목록 조회: 생성 시각·ID 내림차순 20개, 커서(D18). | 없음 | - | **변경** | `GET /me/notifications` 제외(팀 결정) |
| NTF-02 | 같은 이벤트·수신자 중복 알림은 한 건. 7일(168시간) 보관 후 제외·삭제. 요청은 남는다. | 유니크(event_id, recipient), Notification.RETENTION, NotificationCleanupJob | SubstituteSchemaTest#같은_이벤트_같은_수신자에게_알림은_한_건만_저장된다, Noti#생성_후_정확히_7일이_되면_탭_점과_카드_점에서_빠진다, NotificationCleanupJobTest 전체 | 충족 | |
| NTF-03 | 알림 카드 선택은 관련 상세로 이동하고, 수신·요청 접근 권한은 따로 검증한다. | NotificationService:check + QueryService:getDetail | Noti#상세를_열어_확인하면_그_카드_점만_지워진다, #관계없는_요청이나_없는_요청을_확인해도_204이고_아무것도_바뀌지_않는다 | **변경** | 알림 카드 대신 요청 카드. 카드 점(checked_at) 추가 |
| NTF-04 | 새 요청은 지정 크루원에게 보낸다. | CommandService:create | Create#요청을_만들면_WAITING_상세와_대상자_알림이_생긴다 | 충족 | |
| NTF-04 | 결과 알림은 요청자·현재 또는 직전 후보·소속 매니저 합집합, 한 사람 한 건, 후보가 없으면 넓히지 않음, 탈퇴·소속 해제자 제외. | TransitionService:resultRecipients | TransitionResultRecipientsTest 전체 | 충족 | |
| NTF-04 | **행위자 본인도 포함한다.** | resultRecipients(actor 제거) | TransitionResultRecipientsTest#후보가_수락하면_후보_본인은_받지_않는다, Noti#본인이_한_행동으로는_본인_점이_켜지지_않는다 | **변경** | 2026-11-11 결정: 알림 목록이 없어 본인 행동으로 점을 켜지 않는다. 정책 7절·10절 "처리 매니저 본인에게 결과 알림"과도 다름 |
| NTF-04 | 만료·근무 삭제는 알림 없음. 실패한 처리는 알림을 만들지 않는다. | RequestEventKind:createsNotification, 같은 트랜잭션 저장 | NotificationTest#만료와_근무_삭제_이벤트로는_알림을_만들지_않는다, Create#실패한_요청은_아무것도_남기지_않는다 | 충족 | |
| NTF-05 | 최근 7일 미읽음 알림이 있을 때만 점. 승인 대기만으로 점을 유지하지 않는다(N07). | NotificationService:hasUnread | Noti#알림이_없으면_탭_점이_없다, #생성_후_정확히_7일이_되면_탭_점과_카드_점에서_빠진다 | 충족 | |
| NTF-05 | 동시 진입·확인에도 정상 처리 | Repository(id 오름차순 잠금) | NotificationConcurrencyTest 전체 | 충족 | |
| NTF-05 | 빈 상태·조회 중·실패 구분 | - | - | 범위외 | 프론트 |
| N04 | 알림 수신자와 인증 사용자의 활성 소속 일치. 소속 해제 후 우회 불가. | NotificationService:activeMembership(현재 소속만) | Noti#매장_소속이_없으면_403 | 충족 | |

## 4. 정책 항목

| 정책 ID | 정책 문장(요약) | 구현 위치 | 검증 테스트 | 판정 | 비고 |
| --- | --- | --- | --- | --- | --- |
| P06·D09 | 크루원만 요청·후보, 매니저는 승인·거절만 | CommandService·TransitionService(역할 확인) | Create#매니저는_요청할_수_없다, Tr#크루원은_승인_최종거절할_수_없다 | 충족 | |
| P08·B11 | 수락만으로 담당자가 바뀌지 않고 매니저 승인 시에만 변경 | SubstituteRequest:accept·approve | Tr 수락·승인 테스트 | 충족 | |
| B01·B02 | ID로 권한 판정, 다른 매장 접근 불가 | MembershipQuery:requireActive | Query·Tr·Create의 다른_매장_사용자는_403 | 충족 | |
| B20 | 이미 처리된 행동·오래된 버전·중복 제출은 중복 변경하지 않고 최신 상태 반환 | 멱등성 키(생성·전이 7개), current_state | Tr·Create 멱등성 테스트 | 충족 | 요청 본문 버전은 받지 않음(2026-11-10 결정) |
| B21·D05 | 탈퇴 시 본인 수신 알림 파기 | LifecycleService:anonymizeFor | Life#탈퇴_파기는_본인이_받은_알림만_지운다 | 충족 | |
| B21·D05 | **근무·요청·처리 기록에도 이름과 개인 연결 ID를 남기지 않는다.** 재식별 가능하면 기록 삭제 | 요청·진행 기록의 membership_id 유지 | - | **변경(임시)** | [반영] 2026-11-12 임시 결정: membership_id 유지. 탈퇴 시 끊기는 개인 연결은 memberships.user_id로 본다(A의 AccountService.withdraw가 user_id·이름·소셜 계정을 파기함을 코드로 확인). 근거는 backend-split.md "B 데이터 모델" 기타 결정 → PM 확인 |
| 5절 | 종료 상태의 중복 처리·늦은 응답은 추가 변경 없음 | requireOpen | Req#종료된_요청은_어떤_행동으로도_덮어쓰지_않는다 | 충족 | |
| 10절 | 알림 27개 중 20개만 보고 진입해도 27개 모두 읽음 | markAllRead | Noti#새_요청을_받으면_탭_점이_켜지고_요청_탭에_들어가면_꺼진다 | 충족 | 목록이 없으므로 진입 = 요청 탭 진입 |
| 10절 | 승인 대기 알림을 읽어도 승인 대기 유지 | 읽음과 요청 상태 분리 | Noti#첫_수락이_생기면_매니저에게도_탭_점이_켜진다 | 충족 | |
| 10절 | 최종 승인 시 요청자·후보·**처리 매니저 본인**에게 결과 알림 | resultRecipients | Tr#승인하면_그_근무만_담당자가_후보로_바뀌고_처리한_매니저를_뺀_관계자에게_알림 | **변경** | NTF-04와 같은 결정 |

---

## 결정 결과 (2026-11-12 B, PM 부재 중)

| # | 항목 | 결정 | 상태 |
| --- | --- | --- | --- |
| 1 | 탈퇴 후 요청·진행 기록의 membership_id | 유지. 개인 연결은 user_id로 끊는다(익명 값으로 바꿔도 재식별 위험은 같고, 기록마다 바꾸면 관계가 깨진다) | 임시 결정, PM 확인 필요 |
| 2 | 소속 해제로 거절한 대상만 남는 경우 | ALL_DECLINED 유지. 거절 API와 같은 규칙이고, 방치 대신 요청자에게 결과를 알린다 | 임시 결정, PM 확인 필요 |
| 3 | 승인·최종 거절 중 매니저 소속 잠금 | 행위자 소속을 역할과 관계없이 잠근다. 잠금 순서 변화 없음 | 코드 반영, 테스트 추가 |

## PM 문서 반영·확인이 필요한 변경
- (임시) 탈퇴 후 membership_id 유지 (D05, 8절)
- (임시) 소속 해제로 거절한 대상만 남으면 ALL_DECLINED (SUB-10, D20, 4절·5절 상태표)
- NT-01 알림 목록과 `GET /me/notifications` 제외 → 요청 탭 점(open)·요청 카드 점(check) 두 단계 (NTF-01~03, D18, N02·N03, 10절)
- 결과 알림에서 행위자 본인 제외 (NTF-04, D16, N05, 7절, 10절)
