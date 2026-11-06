-- 개발자 A 소유 테이블: 회원·인증·동의·매장·소속·초대코드·근무·근무 이벤트
-- PK는 테이블별 시퀀스(INCREMENT 50) + Hibernate pooled optimizer(allocationSize = 50)
-- FK 삭제 규칙은 모두 NO ACTION. 탈퇴·파기는 서비스에서 명시적으로 처리한다.

CREATE SEQUENCE users_seq             START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE social_accounts_seq   START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE consent_documents_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE user_consents_seq     START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE auth_sessions_seq     START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE stores_seq            START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE memberships_seq       START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE invite_codes_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE shifts_seq            START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE shift_events_seq      START WITH 1 INCREMENT BY 50;

-- 회원 ---------------------------------------------------------------
CREATE TABLE users (
    id               bigint       PRIMARY KEY,
    display_name     varchar(15),
    onboarding_stage varchar(30)  NOT NULL,
    selected_role    varchar(10),
    account_state    varchar(20)  NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    withdrawn_at     timestamptz,
    CONSTRAINT users_display_name_len_ck CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 2 AND 15),
    CONSTRAINT users_onboarding_stage_ck CHECK (onboarding_stage IN
        ('CONSENT_REQUIRED', 'NAME_REQUIRED', 'ROLE_REQUIRED', 'STORE_REQUIRED', 'COMPLETED')),
    CONSTRAINT users_selected_role_ck    CHECK (selected_role IN ('MANAGER', 'CREW')),
    CONSTRAINT users_account_state_ck    CHECK (account_state IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT users_withdrawn_at_ck     CHECK ((account_state = 'WITHDRAWN') = (withdrawn_at IS NOT NULL))
);

CREATE TABLE social_accounts (
    id               bigint       PRIMARY KEY,
    user_id          bigint       NOT NULL REFERENCES users (id),
    provider         varchar(10)  NOT NULL,
    provider_subject varchar(255) NOT NULL,
    created_at       timestamptz  NOT NULL,
    CONSTRAINT social_accounts_provider_ck CHECK (provider IN ('GOOGLE', 'APPLE')),
    CONSTRAINT social_accounts_provider_subject_uk UNIQUE (provider, provider_subject)
);
CREATE INDEX social_accounts_user_id_idx ON social_accounts (user_id);

CREATE TABLE consent_documents (
    id             bigint       PRIMARY KEY,
    kind           varchar(30)  NOT NULL,
    version        integer      NOT NULL,
    required       boolean      NOT NULL,
    title          varchar(100) NOT NULL,
    body_reference varchar(500) NOT NULL,
    effective_at   timestamptz  NOT NULL,
    CONSTRAINT consent_documents_kind_ck    CHECK (kind ~ '^[A-Z_]{1,30}$'),
    CONSTRAINT consent_documents_version_ck CHECK (version > 0),
    CONSTRAINT consent_documents_kind_version_uk UNIQUE (kind, version)
);

CREATE TABLE user_consents (
    id          bigint      PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES users (id),
    document_id bigint      NOT NULL REFERENCES consent_documents (id),
    agreed      boolean     NOT NULL,
    agreed_at   timestamptz NOT NULL,
    CONSTRAINT user_consents_user_document_uk UNIQUE (user_id, document_id)
);
COMMENT ON COLUMN user_consents.agreed_at IS '동의 여부(agreed true/false)를 결정한 시각. 거부한 경우에도 기록한다.';

CREATE TABLE auth_sessions (
    id                 bigint      PRIMARY KEY,
    user_id            bigint      NOT NULL REFERENCES users (id),
    refresh_token_hash char(64)    NOT NULL,
    expires_at         timestamptz NOT NULL,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL,
    CONSTRAINT auth_sessions_hash_format_ck CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_sessions_refresh_token_hash_uk UNIQUE (refresh_token_hash)
);
CREATE INDEX auth_sessions_user_id_idx ON auth_sessions (user_id);

-- 매장·소속 -----------------------------------------------------------
CREATE TABLE stores (
    id         bigint      PRIMARY KEY,
    name       varchar(15) NOT NULL,
    timezone   varchar(40) NOT NULL DEFAULT 'Asia/Seoul',
    state      varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT stores_name_len_ck CHECK (char_length(name) BETWEEN 1 AND 15),
    CONSTRAINT stores_state_ck    CHECK (state IN ('ACTIVE'))
);

CREATE TABLE memberships (
    id             bigint      PRIMARY KEY,
    store_id       bigint      NOT NULL REFERENCES stores (id),
    user_id        bigint      REFERENCES users (id),
    role           varchar(10) NOT NULL,
    state          varchar(20) NOT NULL,
    personal_color varchar(7)  NOT NULL,
    joined_at      timestamptz NOT NULL,
    left_at        timestamptz,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT memberships_role_ck        CHECK (role IN ('MANAGER', 'CREW')),
    CONSTRAINT memberships_state_ck       CHECK (state IN ('ACTIVE', 'LEFT', 'WITHDRAWN')),
    CONSTRAINT memberships_color_ck       CHECK (personal_color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT memberships_active_user_ck CHECK (state <> 'ACTIVE' OR user_id IS NOT NULL),
    CONSTRAINT memberships_left_at_ck     CHECK ((state = 'ACTIVE') = (left_at IS NULL)),
    CONSTRAINT memberships_id_store_uk    UNIQUE (id, store_id)
);
-- 같은 매장 활성 소속 중복 방지 + 매장 구성원 목록 조회
CREATE UNIQUE INDEX memberships_store_user_active_uk ON memberships (store_id, user_id) WHERE state = 'ACTIVE';
-- MVP: 계정당 활성 매장 1개
CREATE UNIQUE INDEX memberships_user_active_uk       ON memberships (user_id)           WHERE state = 'ACTIVE';
-- 탈퇴 파기 시 과거 소속 포함 전체 조회
CREATE INDEX        memberships_user_id_idx          ON memberships (user_id);

CREATE TABLE invite_codes (
    id             bigint      PRIMARY KEY,
    store_id       bigint      NOT NULL REFERENCES stores (id),
    code           char(8)     NOT NULL,
    created_at     timestamptz NOT NULL,
    invalidated_at timestamptz,
    CONSTRAINT invite_codes_code_format_ck CHECK (code ~ '^[A-Z0-9]{8}$'),
    CONSTRAINT invite_codes_code_uk UNIQUE (code)
);
-- 매장별 현재 유효 코드 1개
CREATE UNIQUE INDEX invite_codes_store_valid_uk ON invite_codes (store_id) WHERE invalidated_at IS NULL;

-- 근무 ---------------------------------------------------------------
CREATE TABLE shifts (
    id                     bigint      PRIMARY KEY,
    store_id               bigint      NOT NULL REFERENCES stores (id),
    assignee_membership_id bigint      NOT NULL,
    starts_at              timestamptz NOT NULL,
    ends_at                timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    deleted_at             timestamptz,
    created_at             timestamptz NOT NULL,
    CONSTRAINT shifts_duration_ck CHECK (ends_at > starts_at AND ends_at - starts_at < interval '24 hours'),
    -- 담당자는 반드시 같은 매장 소속
    CONSTRAINT shifts_assignee_same_store_fk FOREIGN KEY (assignee_membership_id, store_id)
        REFERENCES memberships (id, store_id)
);
-- 완전 동일 유효 근무 중복 방지. 삭제된 근무와 같은 시간 재등록은 허용
CREATE UNIQUE INDEX shifts_identical_live_uk     ON shifts (store_id, assignee_membership_id, starts_at, ends_at) WHERE deleted_at IS NULL;
-- 매장 근무표(월 범위) 조회
CREATE INDEX        shifts_store_starts_live_idx ON shifts (store_id, starts_at) WHERE deleted_at IS NULL;
-- 나가기·탈퇴 전 미종료 본인 배정 근무 확인
CREATE INDEX        shifts_assignee_starts_idx   ON shifts (assignee_membership_id, starts_at);

CREATE TABLE shift_events (
    id                  bigint      PRIMARY KEY,
    shift_id            bigint      NOT NULL REFERENCES shifts (id),
    event_kind          varchar(20) NOT NULL,
    before_value        jsonb,
    after_value         jsonb,
    actor_membership_id bigint      REFERENCES memberships (id),
    request_id          bigint,     -- B의 substitute_requests.id. 소유 경계 때문에 FK를 걸지 않는다
    occurred_at         timestamptz NOT NULL,
    CONSTRAINT shift_events_kind_ck CHECK (event_kind IN ('CREATED', 'DELETED', 'REASSIGNED'))
);
CREATE INDEX shift_events_shift_idx ON shift_events (shift_id, occurred_at);
