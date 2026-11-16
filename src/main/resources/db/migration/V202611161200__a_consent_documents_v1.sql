-- 운영 약관 v1. 본문은 프론트 레포의 GitHub Pages(https://swyp-2.github.io/frontend/)에 있다.
-- 운영 DB에 같은 (kind, version) 행을 손으로 넣어 두었어도 실패하지 않도록 덮어쓴다.
-- effective_at은 노출 시작 시각이다(본문의 시행일은 2026-11-16). 시각을 과거로 고정한 테스트에서도 보이도록 11-01로 둔다.
INSERT INTO consent_documents (id, kind, version, required, title, body_reference, effective_at)
VALUES (nextval('consent_documents_seq'), 'TERMS_OF_SERVICE', 1, true, '서비스 이용약관',
        'https://swyp-2.github.io/frontend/terms/v1/', TIMESTAMPTZ '2026-11-01 00:00:00+09'),
       (nextval('consent_documents_seq'), 'PRIVACY_POLICY', 1, true, '개인정보 처리방침',
        'https://swyp-2.github.io/frontend/privacy/v1/', TIMESTAMPTZ '2026-11-01 00:00:00+09')
ON CONFLICT (kind, version) DO UPDATE
    SET title          = EXCLUDED.title,
        body_reference = EXCLUDED.body_reference,
        required       = EXCLUDED.required,
        effective_at   = EXCLUDED.effective_at;
