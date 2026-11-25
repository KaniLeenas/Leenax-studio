-- Leenax AI Video Studio — Phase 4: multi-track video editor and rendering

-- ---------------------------------------------------------------------------
-- timelines: editor documents. `document` is the editor's JSON (tracks and clips),
-- shared with the renderer. `revision` guards against two tabs overwriting each other.
-- ---------------------------------------------------------------------------
CREATE TABLE timelines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        UUID          REFERENCES projects (id) ON DELETE SET NULL,
    name              VARCHAR(160)  NOT NULL,
    width             INTEGER       NOT NULL CHECK (width BETWEEN 64 AND 4096),
    height            INTEGER       NOT NULL CHECK (height BETWEEN 64 AND 4096),
    fps               INTEGER       NOT NULL CHECK (fps IN (24, 25, 30, 50, 60)),
    document          JSONB         NOT NULL,
    revision          INTEGER       NOT NULL DEFAULT 0,
    duration_seconds  NUMERIC(10,3) NOT NULL DEFAULT 0,
    poster_media_id   UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_timelines_updated ON timelines (updated_at DESC);
CREATE INDEX idx_timelines_project ON timelines (project_id);

-- ---------------------------------------------------------------------------
-- render_jobs: exports. The timeline is copied at submit time, so later edits
-- don't change a render in progress. Leased by the worker like generation jobs.
-- ---------------------------------------------------------------------------
CREATE TABLE render_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timeline_id      UUID          NOT NULL REFERENCES timelines (id) ON DELETE CASCADE,
    timeline_name    VARCHAR(160)  NOT NULL,
    project_id       UUID          REFERENCES projects (id) ON DELETE SET NULL,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('QUEUED', 'RENDERING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    progress         NUMERIC(5,4)  NOT NULL DEFAULT 0,
    settings         JSONB         NOT NULL,
    document         JSONB         NOT NULL,
    output_media_id  UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    error_message    VARCHAR(500),
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ,
    submitted_at     TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_render_jobs_due ON render_jobs (next_attempt_at) WHERE status IN ('QUEUED', 'RENDERING');
CREATE INDEX idx_render_jobs_timeline ON render_jobs (timeline_id, created_at DESC);
