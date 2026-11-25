-- Leenax AI Video Studio — Phase 6: social accounts, publishing and the calendar

-- ---------------------------------------------------------------------------
-- social_accounts: channels, Pages and profiles connected through each
-- platform's official sign-in. Nothing on this table is secret.
-- ---------------------------------------------------------------------------
CREATE TABLE social_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform            VARCHAR(12)   NOT NULL CHECK (platform IN ('YOUTUBE', 'FACEBOOK', 'INSTAGRAM', 'TIKTOK')),
    external_id         VARCHAR(120)  NOT NULL,
    display_name        VARCHAR(200)  NOT NULL,
    username            VARCHAR(120),
    avatar_url          VARCHAR(1000),
    status              VARCHAR(16)   NOT NULL CHECK (status IN ('CONNECTED', 'NEEDS_RECONNECT')),
    scopes              VARCHAR(500),
    access_expires_at   TIMESTAMPTZ,
    refresh_expires_at  TIMESTAMPTZ,
    last_error          VARCHAR(500),
    details             JSONB         NOT NULL DEFAULT '{}'::jsonb,
    connected_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (platform, external_id)
);

-- ---------------------------------------------------------------------------
-- social_tokens: OAuth tokens, AES-256-GCM encrypted with LEENAX_MASTER_KEY
-- (associated data "social_account:<id>:access|refresh").
-- ---------------------------------------------------------------------------
CREATE TABLE social_tokens (
    account_id          UUID PRIMARY KEY REFERENCES social_accounts (id) ON DELETE CASCADE,
    access_ciphertext   BYTEA        NOT NULL,
    refresh_ciphertext  BYTEA,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- oauth_states: sign-ins in progress. Only the SHA-256 of the state value is
-- kept; rows are single-use and expire after ten minutes.
-- ---------------------------------------------------------------------------
CREATE TABLE oauth_states (
    state_hash     VARCHAR(64) PRIMARY KEY,
    provider       VARCHAR(8)   NOT NULL CHECK (provider IN ('GOOGLE', 'META', 'TIKTOK')),
    code_verifier  VARCHAR(128),
    expires_at     TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- posts: one video going to one or more accounts.
-- ---------------------------------------------------------------------------
CREATE TABLE posts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(160) NOT NULL,
    media_id            UUID         REFERENCES media_assets (id) ON DELETE SET NULL,
    thumbnail_media_id  UUID         REFERENCES media_assets (id) ON DELETE SET NULL,
    status              VARCHAR(12)  NOT NULL CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHING', 'PUBLISHED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    scheduled_at        TIMESTAMPTZ,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_posts_scheduled ON posts (scheduled_at);
CREATE INDEX idx_posts_published ON posts (published_at);
CREATE INDEX idx_posts_updated ON posts (updated_at DESC);

-- ---------------------------------------------------------------------------
-- post_targets: the post on one account, with that platform's settings.
-- state remembers upload progress (session URL, container id, publish id) so a
-- retry resumes instead of posting twice.
-- ---------------------------------------------------------------------------
CREATE TABLE post_targets (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id        UUID          NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    account_id     UUID          REFERENCES social_accounts (id) ON DELETE SET NULL,
    platform       VARCHAR(12)   NOT NULL CHECK (platform IN ('YOUTUBE', 'FACEBOOK', 'INSTAGRAM', 'TIKTOK')),
    account_name   VARCHAR(200)  NOT NULL,
    status         VARCHAR(14)   NOT NULL CHECK (status IN ('DRAFT', 'SCHEDULED', 'UPLOADING', 'PROCESSING', 'AWAITING_USER', 'PUBLISHED', 'FAILED', 'CANCELLED')),
    settings       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    state          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    run_at         TIMESTAMPTZ,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    remote_id      VARCHAR(200),
    remote_url     VARCHAR(1000),
    step           VARCHAR(200),
    progress       NUMERIC(5,4)  NOT NULL DEFAULT 0,
    error_message  VARCHAR(500),
    warning        VARCHAR(500),
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_post_targets_post ON post_targets (post_id);
CREATE INDEX idx_post_targets_account ON post_targets (account_id);
CREATE INDEX idx_post_targets_due ON post_targets (run_at)
    WHERE status IN ('SCHEDULED', 'UPLOADING', 'PROCESSING', 'AWAITING_USER');
