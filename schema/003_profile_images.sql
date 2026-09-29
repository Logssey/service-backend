ALTER TABLE listing_images
    ADD COLUMN purpose VARCHAR(10) NOT NULL DEFAULT 'LISTING',
    ADD COLUMN profile_user_id BIGINT,
    ADD CONSTRAINT ck_images_purpose CHECK (purpose IN ('LISTING', 'PROFILE')),
    ADD CONSTRAINT fk_images_profile_user FOREIGN KEY (profile_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_images_attachment CHECK (
        (purpose = 'LISTING' AND profile_user_id IS NULL) OR
        (purpose = 'PROFILE' AND listing_id IS NULL AND (profile_user_id IS NULL OR profile_user_id = uploader_id))
    );
CREATE UNIQUE INDEX uq_images_profile_user ON listing_images(profile_user_id) WHERE profile_user_id IS NOT NULL;
COMMENT ON COLUMN listing_images.purpose IS 'LISTING: 상품, PROFILE: 프로필. 기존 상품 이미지 기본값 유지';
COMMENT ON COLUMN listing_images.profile_user_id IS '현재 프로필 연결. 교체·해제·탈퇴 시 NULL로 해제 후 고아 정리';
