-- 온보딩 단계 전이(동의·이름·역할·매장 연결)의 동시 갱신 감지를 위한 낙관적 잠금 버전
ALTER TABLE users ADD COLUMN version bigint NOT NULL DEFAULT 0;
