-- Leenax AI Video Studio — Phase 7: analytics, two-step sign-in, monitoring

-- ---------------------------------------------------------------------------
-- post_metrics: performance numbers read from each platform's official API.
-- One row per published target per collection; NULL = the platform did not
-- report that number (not zero).
-- ---------------------------------------------------------------------------
CREATE TABLE post_metrics (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_id     UUID         NOT NULL REFERENCES post_targets (id) ON DELETE CASCADE,
    platform      VARCHAR(12)  NOT NULL,
    views         BIGINT,
    likes         BIGINT,
    comments      BIGINT,
    shares        BIGINT,
    saves         BIGINT,
    reach         BIGINT,
    details       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    collected_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_post_metrics_target ON post_metrics (target_id, collected_at DESC);
CREATE INDEX idx_post_metrics_collected ON post_metrics (collected_at DESC);

ALTER TABLE post_targets ADD COLUMN metrics_checked_at TIMESTAMPTZ;
ALTER TABLE post_targets ADD COLUMN metrics_note VARCHAR(300);

-- ---------------------------------------------------------------------------
-- Two-step sign-in (TOTP, RFC 6238). Secrets are AES-256-GCM encrypted with
-- LEENAX_MASTER_KEY; recovery codes are stored as SHA-256 hashes.
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN mfa_secret          BYTEA;
ALTER TABLE users ADD COLUMN mfa_pending_secret  BYTEA;
ALTER TABLE users ADD COLUMN mfa_enabled_at      TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN mfa_last_step       BIGINT;

CREATE TABLE mfa_recovery_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(64)  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mfa_recovery_user ON mfa_recovery_codes (user_id);

CREATE INDEX idx_audit_action ON audit_logs (action, created_at DESC);
CREATE INDEX idx_post_targets_published ON post_targets (published_at) WHERE status = 'PUBLISHED';
