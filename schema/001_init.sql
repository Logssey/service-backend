-- ============================================================
-- Re:Used 중고거래 플랫폼 DB 스키마
-- PostgreSQL 18.6
-- DB명: reused
-- 파일: schema/001_init.sql
-- ============================================================

-- ------------------------------------------------------------
-- 0. 데이터베이스 생성 (이 스크립트와 별도로, 먼저 1회만 실행)
-- ------------------------------------------------------------
--   CREATE DATABASE reused;
--   \c reused
--
-- CREATE DATABASE는 다른 DB에 접속한 상태에서 실행하는 명령이라
-- 이 스크립트 파일 안에 넣지 않음.

-- ------------------------------------------------------------
-- (선택) 초기화용 DROP — 개발 중 스키마 재실행 시에만 주석 해제
-- 운영 환경에서는 절대 실행 금지 (데이터 전체 삭제됨)
-- ------------------------------------------------------------
-- DROP TABLE IF EXISTS audit_logs CASCADE;
-- DROP TABLE IF EXISTS notices CASCADE;
-- DROP TABLE IF EXISTS notification_settings CASCADE;
-- DROP TABLE IF EXISTS notifications CASCADE;
-- DROP TABLE IF EXISTS blocks CASCADE;
-- DROP TABLE IF EXISTS reports CASCADE;
-- DROP TABLE IF EXISTS community_comments CASCADE;
-- DROP TABLE IF EXISTS community_posts CASCADE;
-- DROP TABLE IF EXISTS reviews CASCADE;
-- DROP TABLE IF EXISTS messages CASCADE;
-- DROP TABLE IF EXISTS chat_rooms CASCADE;
-- DROP TABLE IF EXISTS trade_status_histories CASCADE;
-- DROP TABLE IF EXISTS trades CASCADE;
-- DROP TABLE IF EXISTS wishes CASCADE;
-- DROP TABLE IF EXISTS listing_images CASCADE;
-- DROP TABLE IF EXISTS listings CASCADE;
-- DROP TABLE IF EXISTS categories CASCADE;
-- DROP TABLE IF EXISTS user_status_histories CASCADE;
-- DROP TABLE IF EXISTS user_identities CASCADE;
-- DROP TABLE IF EXISTS users CASCADE;

-- ------------------------------------------------------------
-- 1. users (회원)
-- ------------------------------------------------------------
CREATE TABLE users (
    user_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nickname            VARCHAR(20)  NOT NULL,
    profile_image_url   TEXT,
    bio                 VARCHAR(200),
    role                VARCHAR(10)  NOT NULL DEFAULT 'USER',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    suspended_until     TIMESTAMPTZ,
    terms_agreed_at     TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    withdrawn_at        TIMESTAMPTZ,

    CONSTRAINT uq_users_nickname UNIQUE (nickname),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
);

COMMENT ON TABLE  users IS '회원. 인증 수단은 user_identities에 분리해 둔다 (ADR-017)';
COMMENT ON COLUMN users.nickname IS '중복 불허. 탈퇴 시 "탈퇴회원#{user_id}"로 대체';
COMMENT ON COLUMN users.suspended_until IS '이용정지 종료 시각. NULL이면 무기한';
COMMENT ON COLUMN users.withdrawn_at IS '탈퇴 시각. 값이 있으면 로그인 차단';

-- ------------------------------------------------------------
-- 2. user_identities (인증 수단)
-- ------------------------------------------------------------
CREATE TABLE user_identities (
    identity_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    provider_user_id    VARCHAR(255) NOT NULL,
    email               VARCHAR(254),
    password_hash       VARCHAR(255),
    email_verified_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_identities_provider_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_user_identities_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_user_identities_provider CHECK (provider IN ('KAKAO', 'LOCAL')),
    CONSTRAINT ck_user_identities_local_email CHECK (
        provider <> 'LOCAL' OR email IS NOT NULL),
    CONSTRAINT ck_user_identities_social_credential CHECK (
        provider = 'LOCAL' OR (email IS NULL AND password_hash IS NULL)),
    CONSTRAINT fk_user_identities_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

-- 이메일은 LOCAL 범위에서만 유일하다. 이메일 로그인 조회 인덱스를 겸한다.
CREATE UNIQUE INDEX uq_user_identities_email_local
    ON user_identities (email) WHERE provider = 'LOCAL';

COMMENT ON TABLE  user_identities IS '인증 수단. 구조상 회원당 복수 행이 가능하나 1차 릴리스는 애플리케이션이 1개로 제한한다 (ADR-017). 탈퇴 시 행을 삭제한다 (ADR-018)';
COMMENT ON COLUMN user_identities.provider_user_id IS 'KAKAO는 회원번호, LOCAL은 서버 발급 UUID';
COMMENT ON COLUMN user_identities.email IS 'LOCAL 필수. LOCAL 범위에서만 유일';
COMMENT ON COLUMN user_identities.password_hash IS 'LOCAL 필수. 고유 Salt 적응형 단방향 해시';
COMMENT ON COLUMN user_identities.email_verified_at IS '이메일 소유 확인 시각. NULL이면 미인증';

-- ------------------------------------------------------------
-- 3. user_status_histories (회원 상태·역할 변경 이력)
-- ------------------------------------------------------------
CREATE TABLE user_status_histories (
    history_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    change_type     VARCHAR(20)  NOT NULL,
    before_value    VARCHAR(20)  NOT NULL,
    after_value     VARCHAR(20)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    changed_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_user_status_hist_type CHECK (change_type IN ('STATUS', 'ROLE')),
    CONSTRAINT fk_user_status_hist_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_status_hist_changed_by
        FOREIGN KEY (changed_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  user_status_histories IS '회원 상태 및 역할 변경 이력. 사유 필수';
COMMENT ON COLUMN user_status_histories.changed_by IS '시스템 처리(탈퇴 등) 시 NULL';

-- ------------------------------------------------------------
-- 4. categories (카테고리)
-- ------------------------------------------------------------
CREATE TABLE categories (
    category_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(50) NOT NULL,
    display_order   INTEGER     NOT NULL DEFAULT 0,
    is_active       BOOLEAN     NOT NULL DEFAULT true,

    CONSTRAINT uq_categories_name UNIQUE (name)
);

COMMENT ON TABLE categories IS '고정 목록. 사용자가 추가할 수 없음';

-- ------------------------------------------------------------
-- 5. listings (중고거래 게시글)
-- ------------------------------------------------------------
CREATE TABLE listings (
    listing_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id       BIGINT        NOT NULL,
    category_id     BIGINT        NOT NULL,
    title           VARCHAR(100)  NOT NULL,
    description     VARCHAR(2000) NOT NULL,
    price           INTEGER       NOT NULL,
    item_condition  VARCHAR(20)   NOT NULL,
    trade_method    VARCHAR(20)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE',
    wish_count      INTEGER       NOT NULL DEFAULT 0,
    view_count      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    deleted_by      BIGINT,

    CONSTRAINT ck_listings_price CHECK (price >= 0 AND price <= 100000000),
    CONSTRAINT ck_listings_condition
        CHECK (item_condition IN ('NEW', 'LIKE_NEW', 'USED', 'DAMAGED')),
    CONSTRAINT ck_listings_trade_method
        CHECK (trade_method IN ('DIRECT', 'DELIVERY', 'BOTH')),
    CONSTRAINT ck_listings_status
        CHECK (status IN ('ON_SALE', 'RESERVED', 'COMPLETED', 'HIDDEN')),
    CONSTRAINT ck_listings_wish_count CHECK (wish_count >= 0),
    CONSTRAINT fk_listings_seller
        FOREIGN KEY (seller_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_listings_category
        FOREIGN KEY (category_id) REFERENCES categories (category_id) ON DELETE RESTRICT,
    CONSTRAINT fk_listings_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  listings IS '중고거래 판매 게시글';
COMMENT ON COLUMN listings.status IS '거래 상태 변경에 따라 시스템이 전환. HIDDEN은 관리자 조치 전용';
COMMENT ON COLUMN listings.wish_count IS '파생값. wishes 테이블에서 재계산 가능';
COMMENT ON COLUMN listings.deleted_by IS '작성자 또는 관리자';

CREATE INDEX idx_listings_seller
    ON listings (seller_id);

CREATE INDEX idx_listings_category
    ON listings (category_id);

-- ------------------------------------------------------------
-- 6. listing_images (게시글 이미지)
-- ------------------------------------------------------------
CREATE TABLE listing_images (
    image_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id      BIGINT,
    uploader_id     BIGINT       NOT NULL,
    object_key      VARCHAR(500) NOT NULL,
    thumbnail_key   VARCHAR(500),
    content_type    VARCHAR(100) NOT NULL,
    file_size       BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    display_order   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_listing_images_object_key UNIQUE (object_key),
    CONSTRAINT ck_listing_images_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_listing_images_file_size
        CHECK (file_size > 0 AND file_size <= 10485760),
    CONSTRAINT ck_listing_images_display_order
        CHECK (display_order >= 0 AND display_order <= 4),
    CONSTRAINT fk_listing_images_listing
        FOREIGN KEY (listing_id) REFERENCES listings (listing_id) ON DELETE CASCADE,
    CONSTRAINT fk_listing_images_uploader
        FOREIGN KEY (uploader_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  listing_images IS '게시글 이미지 메타데이터. 원본은 S3에 저장, object_key는 참조 경로';
COMMENT ON COLUMN listing_images.listing_id IS '게시글 연결 전에는 NULL';
COMMENT ON COLUMN listing_images.object_key IS 'S3 오브젝트 키. 서버가 생성하며 사용자 지정 불가';
COMMENT ON COLUMN listing_images.status IS 'Presigned 발급 시 PENDING, 서버 재검증 통과 후 VERIFIED';
COMMENT ON COLUMN listing_images.file_size IS '최대 10MB(10485760 bytes)';

CREATE INDEX idx_listing_images_listing
    ON listing_images (listing_id);

-- ------------------------------------------------------------
-- 7. wishes (관심 상품)
-- ------------------------------------------------------------
CREATE TABLE wishes (
    wish_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    listing_id  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_wishes_user_listing UNIQUE (user_id, listing_id),
    CONSTRAINT fk_wishes_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_wishes_listing
        FOREIGN KEY (listing_id) REFERENCES listings (listing_id) ON DELETE CASCADE
);

COMMENT ON TABLE wishes IS '관심 상품 등록 관계. 동일 사용자·게시글 조합은 1건만';

CREATE INDEX idx_wishes_user
    ON wishes (user_id);

-- ------------------------------------------------------------
-- 8. trades (거래)
-- ------------------------------------------------------------
CREATE TABLE trades (
    trade_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id      BIGINT      NOT NULL,
    seller_id       BIGINT      NOT NULL,
    buyer_id        BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at     TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    closed_by       BIGINT,
    version         INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT ck_trades_status
        CHECK (status IN ('REQUESTED', 'ACCEPTED', 'COMPLETED', 'REJECTED', 'CANCELED')),
    CONSTRAINT ck_trades_different_party CHECK (seller_id <> buyer_id),
    CONSTRAINT fk_trades_listing
        FOREIGN KEY (listing_id) REFERENCES listings (listing_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trades_seller
        FOREIGN KEY (seller_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trades_buyer
        FOREIGN KEY (buyer_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trades_closed_by
        FOREIGN KEY (closed_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  trades IS '거래. 게시글과 분리된 독립 엔티티로 상태와 이력을 보유';
COMMENT ON COLUMN trades.seller_id IS '요청 시점의 판매자. listings 조인 없이 조회 가능하도록 중복 보유';
COMMENT ON COLUMN trades.completed_at IS '구매자 확정 시각. 판매자는 완료 확정 불가';
COMMENT ON COLUMN trades.version IS '낙관적 잠금용. 동시 상태 전이 방지';

-- 게시글당 승인된 거래는 정확히 1개만 존재 (FR-TRADE-003)
-- 동시 승인 요청이 들어와도 두 번째는 DB 레벨에서 실패
CREATE UNIQUE INDEX uq_trades_one_accepted_per_listing
    ON trades (listing_id)
    WHERE status = 'ACCEPTED';

-- 동일 구매자의 중복 요청 방지 (진행 중인 거래 기준)
CREATE UNIQUE INDEX uq_trades_active_per_buyer
    ON trades (listing_id, buyer_id)
    WHERE status IN ('REQUESTED', 'ACCEPTED');

CREATE INDEX idx_trades_buyer
    ON trades (buyer_id);

CREATE INDEX idx_trades_seller
    ON trades (seller_id);

-- ------------------------------------------------------------
-- 9. trade_status_histories (거래 상태 이력)
-- ------------------------------------------------------------
CREATE TABLE trade_status_histories (
    history_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trade_id        BIGINT      NOT NULL,
    before_status   VARCHAR(20),
    after_status    VARCHAR(20) NOT NULL,
    changed_by      BIGINT,
    reason          VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_trade_status_hist_trade
        FOREIGN KEY (trade_id) REFERENCES trades (trade_id) ON DELETE CASCADE,
    CONSTRAINT fk_trade_status_hist_changed_by
        FOREIGN KEY (changed_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  trade_status_histories IS '거래 상태 전이 이력. 덮어쓰지 않고 누적';
COMMENT ON COLUMN trade_status_histories.before_status IS '최초 생성 시 NULL';

-- ------------------------------------------------------------
-- 10. chat_rooms (채팅방)
-- ------------------------------------------------------------
CREATE TABLE chat_rooms (
    chat_room_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id      BIGINT      NOT NULL,
    seller_id       BIGINT      NOT NULL,
    buyer_id        BIGINT      NOT NULL,
    trade_id        BIGINT,
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_chat_rooms_listing_buyer UNIQUE (listing_id, buyer_id),
    CONSTRAINT ck_chat_rooms_different_party CHECK (seller_id <> buyer_id),
    CONSTRAINT fk_chat_rooms_listing
        FOREIGN KEY (listing_id) REFERENCES listings (listing_id) ON DELETE RESTRICT,
    CONSTRAINT fk_chat_rooms_seller
        FOREIGN KEY (seller_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_chat_rooms_buyer
        FOREIGN KEY (buyer_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_chat_rooms_trade
        FOREIGN KEY (trade_id) REFERENCES trades (trade_id) ON DELETE SET NULL
);

COMMENT ON TABLE  chat_rooms IS '1:1 채팅방. 게시글·구매자 조합당 1개';
COMMENT ON COLUMN chat_rooms.trade_id IS '채팅이 거래 요청보다 먼저 시작되므로 NULL 허용';
COMMENT ON COLUMN chat_rooms.last_message_at IS '채팅 목록 정렬용 파생값';

CREATE INDEX idx_chat_rooms_seller
    ON chat_rooms (seller_id);

CREATE INDEX idx_chat_rooms_buyer
    ON chat_rooms (buyer_id);

-- ------------------------------------------------------------
-- 11. messages (메시지)
-- ------------------------------------------------------------
CREATE TABLE messages (
    message_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_room_id    BIGINT        NOT NULL,
    sender_id       BIGINT        NOT NULL,
    content         VARCHAR(1000) NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT fk_messages_chat_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (chat_room_id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  messages IS '채팅 메시지. 저장·조회는 API 서버가 담당하며 채팅 서버는 DB에 접근하지 않음';
COMMENT ON COLUMN messages.read_at IS '1:1 채팅이므로 상대방 읽음 시각 단일 컬럼으로 처리';
COMMENT ON COLUMN messages.deleted_at IS '소프트 삭제. 상대 화면에는 삭제 표시만 노출';

CREATE INDEX idx_messages_room
    ON messages (chat_room_id);

-- ------------------------------------------------------------
-- 12. reviews (후기)
-- ------------------------------------------------------------
CREATE TABLE reviews (
    review_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trade_id        BIGINT       NOT NULL,
    reviewer_id     BIGINT       NOT NULL,
    reviewee_id     BIGINT       NOT NULL,
    rating          SMALLINT     NOT NULL,
    content         VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT uq_reviews_trade_reviewer UNIQUE (trade_id, reviewer_id),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_reviews_different_party CHECK (reviewer_id <> reviewee_id),
    CONSTRAINT fk_reviews_trade
        FOREIGN KEY (trade_id) REFERENCES trades (trade_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_reviewer
        FOREIGN KEY (reviewer_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reviews_reviewee
        FOREIGN KEY (reviewee_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  reviews IS '거래 완료 후 상호 작성. 거래당 각 당사자 1회';
COMMENT ON COLUMN reviews.deleted_at IS '관리자 숨김 처리. 평점 집계에서 제외';

CREATE INDEX idx_reviews_reviewee
    ON reviews (reviewee_id);

-- ------------------------------------------------------------
-- 13. community_posts (커뮤니티 게시글)
-- ------------------------------------------------------------
CREATE TABLE community_posts (
    post_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    author_id       BIGINT        NOT NULL,
    category        VARCHAR(20)   NOT NULL,
    title           VARCHAR(100)  NOT NULL,
    content         VARCHAR(3000) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED',
    comment_count   INTEGER       NOT NULL DEFAULT 0,
    view_count      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    deleted_by      BIGINT,

    CONSTRAINT ck_community_posts_category
        CHECK (category IN ('GENERAL', 'QUESTION', 'TIP', 'SHARE')),
    CONSTRAINT ck_community_posts_title_length
        CHECK (char_length(title) BETWEEN 2 AND 100),
    CONSTRAINT ck_community_posts_content_length
        CHECK (char_length(content) BETWEEN 10 AND 3000),
    CONSTRAINT ck_community_posts_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_community_posts_comment_count
        CHECK (comment_count >= 0),
    CONSTRAINT ck_community_posts_view_count
        CHECK (view_count >= 0),
    CONSTRAINT fk_community_posts_author
        FOREIGN KEY (author_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_posts_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  community_posts IS '커뮤니티 게시글. 공개 조회, 작성자 소프트 삭제';
COMMENT ON COLUMN community_posts.category IS 'GENERAL, QUESTION, TIP, SHARE';
COMMENT ON COLUMN community_posts.status IS 'PUBLISHED: 공개, HIDDEN: 기존 신고 처리 흐름을 통한 관리자 숨김';
COMMENT ON COLUMN community_posts.deleted_at IS '작성자 삭제 시각. 삭제된 게시글은 조회에서 제외';

CREATE INDEX idx_community_posts_author
    ON community_posts (author_id);

CREATE INDEX idx_community_posts_latest
    ON community_posts (created_at DESC, post_id DESC)
    WHERE deleted_at IS NULL AND status = 'PUBLISHED';

CREATE INDEX idx_community_posts_category_latest
    ON community_posts (category, created_at DESC, post_id DESC)
    WHERE deleted_at IS NULL AND status = 'PUBLISHED';

-- ------------------------------------------------------------
-- 14. community_comments (커뮤니티 댓글)
-- ------------------------------------------------------------
CREATE TABLE community_comments (
    comment_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id         BIGINT       NOT NULL,
    author_id       BIGINT       NOT NULL,
    content         VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PUBLISHED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    deleted_by      BIGINT,

    CONSTRAINT ck_community_comments_content_length
        CHECK (char_length(content) BETWEEN 1 AND 500),
    CONSTRAINT ck_community_comments_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT fk_community_comments_post
        FOREIGN KEY (post_id) REFERENCES community_posts (post_id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_comments_author
        FOREIGN KEY (author_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_community_comments_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  community_comments IS '커뮤니티 평면 텍스트 댓글. 대댓글은 지원하지 않음';
COMMENT ON COLUMN community_comments.status IS 'PUBLISHED: 공개, HIDDEN: 기존 신고 처리 흐름을 통한 관리자 숨김';
COMMENT ON COLUMN community_comments.deleted_at IS '작성자 삭제 시각. 삭제된 댓글은 조회에서 제외';

CREATE INDEX idx_community_comments_post_latest
    ON community_comments (post_id, created_at DESC, comment_id DESC)
    WHERE deleted_at IS NULL AND status = 'PUBLISHED';

CREATE INDEX idx_community_comments_author
    ON community_comments (author_id);

-- ------------------------------------------------------------
-- 15. reports (신고)
-- ------------------------------------------------------------
CREATE TABLE reports (
    report_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id     BIGINT       NOT NULL,
    target_type     VARCHAR(20)  NOT NULL,
    target_id       BIGINT       NOT NULL,
    reason_code     VARCHAR(30)  NOT NULL,
    detail          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    handled_by      BIGINT,
    handled_at      TIMESTAMPTZ,
    resolution      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_reports_target_type
        CHECK (target_type IN ('LISTING', 'USER', 'MESSAGE', 'COMMUNITY_POST', 'COMMUNITY_COMMENT')),
    CONSTRAINT ck_reports_status
        CHECK (status IN ('RECEIVED', 'IN_REVIEW', 'RESOLVED', 'REJECTED')),
    CONSTRAINT fk_reports_reporter
        FOREIGN KEY (reporter_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_handled_by
        FOREIGN KEY (handled_by) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  reports IS '중고거래 게시글·사용자·메시지·커뮤니티 게시글·댓글 신고';
COMMENT ON COLUMN reports.target_id IS 'target_type과 함께 다형 참조. FK 없으므로 존재 여부는 애플리케이션에서 검증';

-- 미처리 상태의 동일 신고자·대상·사유 조합 중복 방지
CREATE UNIQUE INDEX uq_reports_pending_duplicate
    ON reports (reporter_id, target_type, target_id, reason_code)
    WHERE status IN ('RECEIVED', 'IN_REVIEW');

-- ------------------------------------------------------------
-- 16. blocks (사용자 차단)
-- ------------------------------------------------------------
CREATE TABLE blocks (
    block_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    blocker_id  BIGINT      NOT NULL,
    blocked_id  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_blocks_pair UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_blocks_not_self CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_blocks_blocker
        FOREIGN KEY (blocker_id) REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_blocks_blocked
        FOREIGN KEY (blocked_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE blocks IS '단방향 차단. 차단당한 쪽은 차단 사실을 알 수 없음';

-- ------------------------------------------------------------
-- 17. notifications (알림)
-- ------------------------------------------------------------
CREATE TABLE notifications (
    notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    title           VARCHAR(100) NOT NULL,
    body            VARCHAR(500),
    target_type     VARCHAR(20),
    target_id       BIGINT,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
);

COMMENT ON TABLE  notifications IS '인앱 알림. 클라이언트가 주기적으로 폴링하여 조회';
COMMENT ON COLUMN notifications.target_id IS '알림 클릭 시 이동할 리소스 식별자';

CREATE INDEX idx_notifications_user
    ON notifications (user_id);

-- ------------------------------------------------------------
-- 18. notification_settings (알림 수신 설정)
-- ------------------------------------------------------------
CREATE TABLE notification_settings (
    user_id         BIGINT      PRIMARY KEY,
    trade_enabled   BOOLEAN     NOT NULL DEFAULT true,
    chat_enabled    BOOLEAN     NOT NULL DEFAULT true,
    review_enabled  BOOLEAN     NOT NULL DEFAULT true,
    report_enabled  BOOLEAN     NOT NULL DEFAULT true,
    notice_enabled  BOOLEAN     NOT NULL DEFAULT true,
    updated_at      TIMESTAMPTZ,

    CONSTRAINT fk_notification_settings_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
);

COMMENT ON TABLE notification_settings IS '유형별 알림 수신 설정. 회원가입 시 기본값으로 1행 생성';

-- ------------------------------------------------------------
-- 19. notices (공지사항)
-- ------------------------------------------------------------
CREATE TABLE notices (
    notice_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    author_id   BIGINT        NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    content     VARCHAR(5000) NOT NULL,
    is_pinned   BOOLEAN       NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ,

    CONSTRAINT fk_notices_author
        FOREIGN KEY (author_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE notices IS '관리자 전용 공지사항';

-- ------------------------------------------------------------
-- 20. audit_logs (감사 로그)
-- ------------------------------------------------------------
CREATE TABLE audit_logs (
    audit_log_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id        BIGINT,
    action          VARCHAR(50) NOT NULL,
    target_type     VARCHAR(20),
    target_id       BIGINT,
    result          VARCHAR(20) NOT NULL,
    ip_address      INET,
    detail          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT fk_audit_logs_actor
        FOREIGN KEY (actor_id) REFERENCES users (user_id) ON DELETE RESTRICT
);

COMMENT ON TABLE  audit_logs IS '인증·게시글·거래·관리자 행위 및 외부 연동 결과 기록';
COMMENT ON COLUMN audit_logs.actor_id IS '시스템 행위는 NULL';
COMMENT ON COLUMN audit_logs.detail IS '부가 정보. 토큰·인증정보·개인정보 원문을 저장하지 않음';

-- ============================================================
-- 끝
-- ============================================================
