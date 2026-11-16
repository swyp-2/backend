-- local·test 프로필 전용 약관 시드.
-- 필수 약관(이용약관·개인정보 처리방침) v1은 운영과 같이 db/migration/V202611161200__a_consent_documents_v1.sql이 넣는다.
-- 여기서는 선택 약관 동작을 확인하기 위한 local 전용 마케팅 동의만 둔다(운영에는 없음). example.com은 자리표시자다.
INSERT INTO consent_documents (id, kind, version, required, title, body_reference, effective_at)
VALUES (nextval('consent_documents_seq'), 'MARKETING', 1, false, '(임시) 마케팅 정보 수신 동의',
        'https://example.com/baton/terms/marketing/v1', TIMESTAMPTZ '2026-11-01 00:00:00+09')
ON CONFLICT (kind, version) DO UPDATE
    SET title          = EXCLUDED.title,
        body_reference = EXCLUDED.body_reference,
        required       = EXCLUDED.required;
