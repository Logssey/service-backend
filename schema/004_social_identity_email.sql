-- ============================================================
-- Re:Used 스키마 변경
-- 파일: schema/004_social_identity_email.sql
-- 소셜 인증 수단에 온보딩에서 사용자가 선택 입력한 이메일과 그 수집·이용 동의 시각을 허용한다 (ADR-019)
-- 비밀번호는 계속 LOCAL 전용이다
-- 소유 확인이 끝난 소셜 이메일은 소셜 인증 수단 전체에서 하나만 둔다. 확인 전 주소와 LOCAL 이메일은 겹쳐도 된다
-- uq_user_identities_email_local 과 ck_user_identities_local_email 은 그대로 둔다
-- 기존 소셜 행은 이메일이 모두 NULL이라 이관 없이 새 제약을 만족한다
-- 제약 완화와 NULL 허용 컬럼 추가라 애플리케이션 배포 전에 적용해도 기존 버전과 호환된다
-- ============================================================

ALTER TABLE user_identities
    DROP CONSTRAINT ck_user_identities_social_credential,
    ADD CONSTRAINT ck_user_identities_social_credential CHECK (
        provider = 'LOCAL' OR password_hash IS NULL),
    ADD CONSTRAINT ck_user_identities_verified_email CHECK (
        email_verified_at IS NULL OR email IS NOT NULL),
    ADD COLUMN email_consent_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_user_identities_social_email_consent CHECK (
        provider = 'LOCAL' OR email IS NULL OR email_consent_at IS NOT NULL);

-- 소유 확인이 끝난 소셜 이메일은 한 계정에만 둔다. 동시에 확인해도 한 건만 통과한다.
CREATE UNIQUE INDEX uq_user_identities_email_social_verified
    ON user_identities (email) WHERE provider <> 'LOCAL' AND email_verified_at IS NOT NULL;

COMMENT ON COLUMN user_identities.email IS 'LOCAL 필수, LOCAL 범위에서만 유일. 소셜은 온보딩 선택 입력 연락처이며 소유 확인이 끝난 주소만 소셜 범위에서 유일 (ADR-019)';
COMMENT ON COLUMN user_identities.password_hash IS 'LOCAL 필수, 소셜은 항상 NULL. 고유 Salt 적응형 단방향 해시';
COMMENT ON COLUMN user_identities.email_verified_at IS '이메일 소유 확인 시각. NULL이면 미인증. 이메일이 없으면 항상 NULL';
COMMENT ON COLUMN user_identities.email_consent_at IS '소셜 이메일 수집·이용 선택 동의 시각. 소셜 인증 수단에 이메일이 있으면 필수. LOCAL은 쓰지 않음 (ADR-019)';
