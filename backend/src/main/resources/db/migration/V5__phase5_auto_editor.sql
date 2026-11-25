-- Leenax AI Video Studio — Phase 5: AI auto-editor

-- ---------------------------------------------------------------------------
-- media_analyses: what the auto-editor learned about a file, kept so the same
-- footage is never transcribed or described (and paid for) twice.
-- ---------------------------------------------------------------------------
CREATE TABLE media_analyses (
    media_id             UUID PRIMARY KEY REFERENCES media_assets (id) ON DELETE CASCADE,
    language             VARCHAR(10),
    transcript           JSONB,          -- {"text": "...", "words": [{"text","start","end"}], "segments": [...]}
    transcript_provider  VARCHAR(30),
    transcribed_at       TIMESTAMPTZ,
    description          TEXT,           -- one-sentence description of the footage, from its frames
    description_model    VARCHAR(120),
    described_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- auto_edit_jobs: one automatic edit request. The result is one or more
-- ordinary editor timelines (result.timelineIds).
-- ---------------------------------------------------------------------------
CREATE TABLE auto_edit_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mode             VARCHAR(20)   NOT NULL CHECK (mode IN ('SCRIPT_TO_VIDEO', 'TALKING_HEAD', 'HIGHLIGHTS')),
    name             VARCHAR(160)  NOT NULL,
    project_id       UUID          REFERENCES projects (id) ON DELETE SET NULL,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    step             VARCHAR(160),
    progress         NUMERIC(5,4)  NOT NULL DEFAULT 0,
    settings         JSONB         NOT NULL,
    result           JSONB,
    error_message    VARCHAR(500),
    attempts         INTEGER       NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_auto_edit_jobs_due ON auto_edit_jobs (created_at) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_auto_edit_jobs_created ON auto_edit_jobs (created_at DESC);
