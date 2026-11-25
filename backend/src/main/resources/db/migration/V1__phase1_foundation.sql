-- Leenax AI Video Studio — Phase 1 schema
-- Single-owner personal platform. UUID keys, TIMESTAMPTZ (UTC) everywhere.
-- Later phases add their own migrations (ai_providers, ai_accounts, generation_jobs, ...).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- users: exactly one owner account. The unique index on a constant makes a
-- second row impossible at the database level.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(254) NOT NULL,
    display_name   VARCHAR(80)  NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_single_owner ON users ((TRUE));
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

-- ---------------------------------------------------------------------------
-- projects
-- ---------------------------------------------------------------------------
CREATE TABLE projects (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'ARCHIVED')),
    aspect_ratio  VARCHAR(10)  NOT NULL DEFAULT 'LANDSCAPE'
                  CHECK (aspect_ratio IN ('LANDSCAPE', 'PORTRAIT', 'SQUARE')),
    language      VARCHAR(5)   NOT NULL DEFAULT 'EN'
                  CHECK (language IN ('EN', 'TA')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_projects_updated ON projects (updated_at DESC);

-- ---------------------------------------------------------------------------
-- media_assets: every file in private object storage.
-- storage_key points into the private bucket; the browser only ever gets
-- short-lived signed URLs.
-- ---------------------------------------------------------------------------
CREATE TABLE media_assets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id         UUID         REFERENCES projects (id) ON DELETE SET NULL,
    kind               VARCHAR(10)  NOT NULL CHECK (kind IN ('VIDEO', 'IMAGE', 'AUDIO')),
    source             VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD'
                       CHECK (source IN ('UPLOAD', 'AI_GENERATED', 'RENDER', 'RECORDING')),
    original_filename  VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    size_bytes         BIGINT       NOT NULL CHECK (size_bytes >= 0),
    checksum_sha256    VARCHAR(64)     NOT NULL,
    storage_key        VARCHAR(512) NOT NULL UNIQUE,
    poster_storage_key VARCHAR(512),
    width              INTEGER,
    height             INTEGER,
    duration_ms        BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_project ON media_assets (project_id);
CREATE INDEX idx_media_created ON media_assets (created_at DESC);
CREATE INDEX idx_media_kind ON media_assets (kind);

-- ---------------------------------------------------------------------------
-- audit_logs: security-relevant events. Never store secrets here.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action       VARCHAR(60)  NOT NULL,
    entity_type  VARCHAR(60),
    entity_id    UUID,
    ip_address   VARCHAR(64),
    details      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON audit_logs (created_at DESC);
