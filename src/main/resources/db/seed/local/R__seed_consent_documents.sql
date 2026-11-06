-- local·test 프로필 전용 약관 시드. 운영 약관 본문이 정해지기 전까지의 임시 값이다.
INSERT INTO consent_documents (id, kind, version, required, title, body_reference, effective_at)
VALUES (nextval('consent_documents_seq'), 'TERMS_OF_SERVICE', 1, true,  '(임시) 서비스 이용약관',        'TBD', TIMESTAMPTZ '2026-11-01 00:00:00+09'),
       (nextval('consent_documents_seq'), 'PRIVACY_POLICY',   1, true,  '(임시) 개인정보 처리방침',      'TBD', TIMESTAMPTZ '2026-11-01 00:00:00+09'),
       (nextval('consent_documents_seq'), 'MARKETING',        1, false, '(임시) 마케팅 정보 수신 동의', 'TBD', TIMESTAMPTZ '2026-11-01 00:00:00+09')
ON CONFLICT (kind, version) DO UPDATE
    SET title          = EXCLUDED.title,
        body_reference = EXCLUDED.body_reference,
        required       = EXCLUDED.required;
