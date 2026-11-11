-- 2026-11-11 Figma 기준 변경: 알림 목록(NT-01) 없이 빨간 점 두 단계로 표시한다.
--   read_at    : 하단 바 "요청" 탭 점. 요청 탭 진입(POST /me/notifications/open) 시 그때까지 저장된 알림을 채운다.
--   checked_at : 요청 카드 점. 그 요청 상세를 열 때(POST /me/substitute-requests/{id}/check) 채운다.
-- 카드를 확인했으면 탭 점도 읽은 것으로 본다.
ALTER TABLE notifications ADD COLUMN checked_at timestamptz;
ALTER TABLE notifications ADD CONSTRAINT notifications_checked_read_ck
    CHECK (checked_at IS NULL OR read_at IS NOT NULL);

-- 탭 점 존재 여부·읽음 일괄 처리
CREATE INDEX notifications_unread_idx ON notifications (recipient_membership_id) WHERE read_at IS NULL;
-- 카드 점(요청별 미확인) 조회·확인 처리
CREATE INDEX notifications_unchecked_idx ON notifications (recipient_membership_id, request_id)
    WHERE checked_at IS NULL;
