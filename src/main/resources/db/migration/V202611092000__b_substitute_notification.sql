-- 개발자 B 소유 테이블: 대타 요청·지정 대상·요청 진행 기록·앱 내부 알림
-- PK는 A와 같은 방식: 테이블별 시퀀스(INCREMENT 50) + Hibernate pooled optimizer(allocationSize = 50)
-- FK 삭제 규칙은 모두 NO ACTION. 탈퇴 파기는 서비스에서 명시적으로 처리한다.
-- 탈퇴 시 A가 memberships.user_id를 비우므로, 여기 남는 membership_id는 개인과 연결되지 않는다.

CREATE SEQUENCE substitute_requests_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE request_recipients_seq  START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE request_events_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE notifications_seq       START WITH 1 INCREMENT BY 50;

-- 대타 요청 -----------------------------------------------------------
CREATE TABLE substitute_requests (
    id                      bigint      PRIMARY KEY,
    store_id                bigint      NOT NULL REFERENCES stores (id),
    shift_id                bigint      NOT NULL REFERENCES shifts (id),
    requester_membership_id bigint      NOT NULL,
    candidate_membership_id bigint,     -- 첫 수락자. 종료 후에도 마지막 후보를 남긴다(직전 후보 알림·진행 기록용)
    status                  varchar(30) NOT NULL,
    terminal_reason         varchar(30),
    -- 요청 생성 시점의 원본 근무 스냅샷. 근무가 삭제돼도 상세에 표시하고, 만료 스케줄러가 source_starts_at으로 찾는다
    source_starts_at        timestamptz NOT NULL,
    source_ends_at          timestamptz NOT NULL,
    source_version          bigint      NOT NULL,
    version                 bigint      NOT NULL DEFAULT 0,
    created_at              timestamptz NOT NULL,
    closed_at               timestamptz,
    CONSTRAINT substitute_requests_status_ck CHECK (status IN (
        'WAITING', 'PENDING_MANAGER',
        'APPROVED', 'REJECTED', 'ALL_DECLINED', 'CANCELLED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED_SOURCE_CHANGED')),
    -- 상태별 허용 사유. CANCELLED_SOURCE_CHANGED는 사유 필수(SHIFT_DELETED)
    CONSTRAINT substitute_requests_reason_ck CHECK (
        (terminal_reason IS NULL AND status <> 'CANCELLED_SOURCE_CHANGED')
        OR (status = 'CANCELLED' AND terminal_reason IN ('REQUESTER_LEFT', 'NO_ACTIVE_TARGET'))
        OR (status = 'WITHDRAWN' AND terminal_reason = 'CANDIDATE_LEFT')
        OR (status = 'CANCELLED_SOURCE_CHANGED' AND terminal_reason = 'SHIFT_DELETED')),
    -- 수락 전에는 후보 없음, 수락 이후 상태에는 후보 필수
    CONSTRAINT substitute_requests_candidate_ck CHECK (
        (status <> 'WAITING' OR candidate_membership_id IS NULL)
        AND (status NOT IN ('PENDING_MANAGER', 'APPROVED', 'REJECTED', 'WITHDRAWN') OR candidate_membership_id IS NOT NULL)),
    CONSTRAINT substitute_requests_candidate_not_requester_ck CHECK (candidate_membership_id <> requester_membership_id),
    CONSTRAINT substitute_requests_closed_at_ck CHECK ((status IN ('WAITING', 'PENDING_MANAGER')) = (closed_at IS NULL)),
    CONSTRAINT substitute_requests_source_duration_ck CHECK (source_ends_at > source_starts_at),
    -- 요청자·후보는 반드시 같은 매장 소속
    CONSTRAINT substitute_requests_requester_same_store_fk FOREIGN KEY (requester_membership_id, store_id)
        REFERENCES memberships (id, store_id),
    CONSTRAINT substitute_requests_candidate_same_store_fk FOREIGN KEY (candidate_membership_id, store_id)
        REFERENCES memberships (id, store_id)
);
-- 근무당 진행 중 요청 1건 (동시 생성에서도 보장)
CREATE UNIQUE INDEX substitute_requests_shift_active_uk ON substitute_requests (shift_id)
    WHERE status IN ('WAITING', 'PENDING_MANAGER');
-- 보낸 요청 목록: 생성 시각·ID 내림차순 커서
CREATE INDEX substitute_requests_requester_created_idx ON substitute_requests (requester_membership_id, created_at DESC, id DESC);
-- 만료 스케줄러: 시작 시각이 지난 진행 중 요청
CREATE INDEX substitute_requests_active_starts_idx ON substitute_requests (source_starts_at)
    WHERE status IN ('WAITING', 'PENDING_MANAGER');
-- 소속 해제 정리: 후보로 잡힌 진행 중 요청
CREATE INDEX substitute_requests_candidate_idx ON substitute_requests (candidate_membership_id)
    WHERE candidate_membership_id IS NOT NULL;

-- 지정 대상 -----------------------------------------------------------
CREATE TABLE request_recipients (
    id                   bigint      PRIMARY KEY,
    request_id           bigint      NOT NULL REFERENCES substitute_requests (id),
    target_membership_id bigint      NOT NULL REFERENCES memberships (id),
    response             varchar(20) NOT NULL,
    responded_at         timestamptz, -- 마지막 수락·거절 시각
    unavailable_at       timestamptz, -- 소속 해제로 응답 불가가 된 시각
    CONSTRAINT request_recipients_response_ck    CHECK (response IN ('PENDING', 'ACCEPTED', 'DECLINED', 'UNAVAILABLE')),
    CONSTRAINT request_recipients_responded_ck   CHECK (response NOT IN ('ACCEPTED', 'DECLINED') OR responded_at IS NOT NULL),
    CONSTRAINT request_recipients_pending_ck     CHECK (response <> 'PENDING' OR responded_at IS NULL),
    CONSTRAINT request_recipients_unavailable_ck CHECK ((response = 'UNAVAILABLE') = (unavailable_at IS NOT NULL)),
    -- 같은 동료 중복 지정 방지
    CONSTRAINT request_recipients_request_target_uk UNIQUE (request_id, target_membership_id)
);
-- 받은 요청 목록·소속 해제 정리
CREATE INDEX request_recipients_target_idx ON request_recipients (target_membership_id, request_id DESC);

-- 요청 진행 기록 ------------------------------------------------------
CREATE TABLE request_events (
    id                  bigint      PRIMARY KEY,
    request_id          bigint      NOT NULL REFERENCES substitute_requests (id),
    actor_membership_id bigint      REFERENCES memberships (id), -- 만료 등 시스템 처리면 NULL
    kind                varchar(30) NOT NULL,
    before_status       varchar(30), -- 요청 생성이면 NULL
    after_status        varchar(30) NOT NULL,
    occurred_at         timestamptz NOT NULL,
    CONSTRAINT request_events_kind_ck CHECK (kind IN (
        'REQUEST_CREATED', 'CANDIDATE_ACCEPTED', 'TARGET_DECLINED', 'REQUEST_CANCELLED', 'CANDIDATE_WITHDRAWN',
        'MANAGER_APPROVED', 'MANAGER_REJECTED', 'REQUEST_EXPIRED', 'SOURCE_DELETED', 'MEMBER_LEFT'))
);
CREATE INDEX request_events_request_idx ON request_events (request_id, occurred_at, id);

-- 앱 내부 알림 --------------------------------------------------------
-- 생성 후 정확히 7일(168시간)에 만료. 만료분은 조회·표시점에서 즉시 제외하고 정리 작업이 삭제한다.
CREATE TABLE notifications (
    id                      bigint      PRIMARY KEY,
    recipient_membership_id bigint      NOT NULL REFERENCES memberships (id),
    request_id              bigint      NOT NULL REFERENCES substitute_requests (id),
    event_id                bigint      NOT NULL REFERENCES request_events (id),
    kind                    varchar(30) NOT NULL, -- 원인 이벤트 종류(목록 표시용 복사본)
    created_at              timestamptz NOT NULL,
    read_at                 timestamptz,
    expires_at              timestamptz NOT NULL,
    -- 만료·근무 삭제는 알림을 만들지 않는다
    CONSTRAINT notifications_kind_ck CHECK (kind IN (
        'REQUEST_CREATED', 'CANDIDATE_ACCEPTED', 'TARGET_DECLINED', 'REQUEST_CANCELLED', 'CANDIDATE_WITHDRAWN',
        'MANAGER_APPROVED', 'MANAGER_REJECTED', 'MEMBER_LEFT')),
    CONSTRAINT notifications_expiry_ck CHECK (expires_at = created_at + interval '168 hours'),
    -- 같은 이벤트·같은 수신자는 한 건 (요청자·후보·매니저 관계가 겹쳐도)
    CONSTRAINT notifications_event_recipient_uk UNIQUE (event_id, recipient_membership_id)
);
-- 알림 목록: 생성 시각·ID 내림차순 커서, 미읽음 표시점
CREATE INDEX notifications_recipient_created_idx ON notifications (recipient_membership_id, created_at DESC, id DESC);
-- 만료 정리
CREATE INDEX notifications_expires_idx ON notifications (expires_at);
