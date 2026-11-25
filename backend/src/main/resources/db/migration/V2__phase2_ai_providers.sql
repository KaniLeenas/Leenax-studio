-- Leenax AI Video Studio — Phase 2: AI providers, accounts, credits, usage, generation jobs

-- ---------------------------------------------------------------------------
-- ai_models: the video/text models you can pick. Editable from the app because
-- providers add and retire models often. Provider protocol code lives in adapters;
-- everything model-specific (durations, ratios, pricing) lives here.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_models (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider      VARCHAR(30)  NOT NULL,
    model_code    VARCHAR(120) NOT NULL,
    display_name  VARCHAR(160) NOT NULL,
    capability    VARCHAR(20)  NOT NULL DEFAULT 'VIDEO' CHECK (capability IN ('VIDEO', 'TEXT')),
    options       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    pricing       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    notes         TEXT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INTEGER      NOT NULL DEFAULT 100,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, model_code)
);

-- ---------------------------------------------------------------------------
-- ai_accounts: several named accounts per provider (e.g. "Runway personal",
-- "Runway free trial"). One default per provider.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_accounts (
    id                   UUID PRIMARY KEY,
    provider             VARCHAR(30)   NOT NULL,
    name                 VARCHAR(80)   NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'UNVERIFIED'
                         CHECK (status IN ('ACTIVE', 'UNVERIFIED', 'INVALID', 'DISABLED')),
    is_default           BOOLEAN       NOT NULL DEFAULT FALSE,
    key_hint             VARCHAR(8),
    low_credit_threshold NUMERIC(14, 2),
    last_verified_at     TIMESTAMPTZ,
    last_error           VARCHAR(300),
    balance_synced_at    TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_ai_accounts_name ON ai_accounts (provider, lower(name));
CREATE UNIQUE INDEX uq_ai_accounts_default ON ai_accounts (provider) WHERE is_default;

-- ---------------------------------------------------------------------------
-- api_credentials: AES-256-GCM ciphertext of the API key. The plaintext key
-- never touches the database, logs, or API responses.
-- ---------------------------------------------------------------------------
CREATE TABLE api_credentials (
    account_id   UUID PRIMARY KEY REFERENCES ai_accounts (id) ON DELETE CASCADE,
    ciphertext   BYTEA       NOT NULL,
    key_version  SMALLINT    NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at   TIMESTAMPTZ
);

-- ---------------------------------------------------------------------------
-- credit_balances: balance "buckets" per account — free trial credits, paid
-- packs, monthly plans — each with its own unit and optional expiry.
-- PROVIDER rows are replaced on every sync; MANUAL rows are yours to edit.
-- ---------------------------------------------------------------------------
CREATE TABLE credit_balances (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID          NOT NULL REFERENCES ai_accounts (id) ON DELETE CASCADE,
    label       VARCHAR(120)  NOT NULL,
    kind        VARCHAR(10)   NOT NULL DEFAULT 'PAID' CHECK (kind IN ('FREE', 'PAID')),
    amount      NUMERIC(14, 2) NOT NULL,
    unit        VARCHAR(20)   NOT NULL CHECK (unit IN ('CREDITS', 'USD', 'SECONDS', 'UNITS')),
    expires_at  TIMESTAMPTZ,
    source      VARCHAR(10)   NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'PROVIDER')),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_credit_balances_account ON credit_balances (account_id);

-- ---------------------------------------------------------------------------
-- generation_jobs: one row per generated video (variations share group_id).
-- A DB lease (locked_until) lets the worker survive restarts without Redis.
-- ---------------------------------------------------------------------------
CREATE TABLE generation_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID         NOT NULL,
    variation_index  SMALLINT     NOT NULL DEFAULT 0,
    account_id       UUID         REFERENCES ai_accounts (id) ON DELETE SET NULL,
    account_name     VARCHAR(80)  NOT NULL,
    provider         VARCHAR(30)  NOT NULL,
    model_id         UUID         REFERENCES ai_models (id) ON DELETE SET NULL,
    model_code       VARCHAR(120) NOT NULL,
    model_name       VARCHAR(160) NOT NULL,
    mode             VARCHAR(20)  NOT NULL CHECK (mode IN ('TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO')),
    prompt           TEXT         NOT NULL,
    negative_prompt  TEXT,
    params           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    input_media_id   UUID         REFERENCES media_assets (id) ON DELETE SET NULL,
    project_id       UUID         REFERENCES projects (id) ON DELETE SET NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'QUEUED'
                     CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    provider_job_id  VARCHAR(200),
    progress         NUMERIC(5, 4),
    attempts         SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_until     TIMESTAMPTZ,
    error_code       VARCHAR(40),
    error_message    VARCHAR(500),
    estimated_cost   NUMERIC(14, 2),
    actual_cost      NUMERIC(14, 2),
    cost_unit        VARCHAR(20),
    output_media_id  UUID         REFERENCES media_assets (id) ON DELETE SET NULL,
    idempotency_key  VARCHAR(100),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at     TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_generation_jobs_due ON generation_jobs (next_attempt_at)
    WHERE status IN ('QUEUED', 'PROCESSING');
CREATE INDEX idx_generation_jobs_created ON generation_jobs (created_at DESC);
CREATE INDEX idx_generation_jobs_group ON generation_jobs (group_id);
CREATE UNIQUE INDEX uq_generation_jobs_idempotency ON generation_jobs (idempotency_key, variation_index)
    WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- usage_logs: what each generation cost, per account. Account and model names
-- are copied so history survives deleting an account.
-- ---------------------------------------------------------------------------
CREATE TABLE usage_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id        UUID         REFERENCES ai_accounts (id) ON DELETE SET NULL,
    account_name      VARCHAR(80)  NOT NULL,
    provider          VARCHAR(30)  NOT NULL,
    model_code        VARCHAR(120) NOT NULL,
    capability        VARCHAR(20)  NOT NULL,
    generation_job_id UUID         REFERENCES generation_jobs (id) ON DELETE SET NULL,
    output_seconds    NUMERIC(10, 2),
    cost              NUMERIC(14, 2),
    cost_unit         VARCHAR(20),
    cost_source       VARCHAR(20)  NOT NULL CHECK (cost_source IN ('PROVIDER_REPORTED', 'ESTIMATED', 'UNKNOWN')),
    success           BOOLEAN      NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_logs_created ON usage_logs (created_at DESC);
CREATE INDEX idx_usage_logs_account ON usage_logs (account_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Seed models from each provider's official docs (checked November 2026).
-- Prices are the providers' list prices; edit them in the app when they change.
-- ---------------------------------------------------------------------------
INSERT INTO ai_models (provider, model_code, display_name, options, pricing, notes, sort_order) VALUES
-- Runway (https://docs.dev.runwayml.com) — 1 credit = US$0.01
('RUNWAY', 'gen4.5', 'Runway Gen-4.5',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"min":2,"max":10},"defaultDuration":5,
   "aspectRatios":{"TEXT_TO_VIDEO":[{"value":"1280:720","label":"16:9 landscape"},{"value":"720:1280","label":"9:16 vertical"}],
                   "IMAGE_TO_VIDEO":[{"value":"1280:720","label":"16:9 landscape"},{"value":"720:1280","label":"9:16 vertical"},{"value":"960:960","label":"1:1 square"},{"value":"1104:832","label":"4:3"},{"value":"832:1104","label":"3:4"},{"value":"1584:672","label":"21:9 cinematic"}]}}',
 '{"unit":"CREDITS","rates":[{"perSecond":12}]}', 'Runway''s own flagship model.', 10),
('RUNWAY', 'gen4_turbo', 'Runway Gen-4 Turbo',
 '{"modes":["IMAGE_TO_VIDEO"],"durations":{"min":2,"max":10},"defaultDuration":5,
   "aspectRatios":{"IMAGE_TO_VIDEO":[{"value":"1280:720","label":"16:9 landscape"},{"value":"720:1280","label":"9:16 vertical"},{"value":"960:960","label":"1:1 square"},{"value":"1104:832","label":"4:3"},{"value":"832:1104","label":"3:4"},{"value":"1584:672","label":"21:9 cinematic"}]}}',
 '{"unit":"CREDITS","rates":[{"perSecond":5}]}', 'Fast and cheap. Image-to-video only.', 20),
('RUNWAY', 'veo3.1', 'Google Veo 3.1 (via Runway)',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"values":[4,6,8]},"defaultDuration":8,"audio":true,
   "aspectRatios":{"ALL":[{"value":"1920:1080","label":"16:9 · 1080p"},{"value":"1080:1920","label":"9:16 · 1080p"},{"value":"1280:720","label":"16:9 · 720p"},{"value":"720:1280","label":"9:16 · 720p"}]}}',
 '{"unit":"CREDITS","rates":[{"perSecond":20,"audio":false},{"perSecond":40,"audio":true}]}', 'Google''s Veo 3.1 through your Runway account. Can generate sound.', 30),
('RUNWAY', 'veo3.1_fast', 'Google Veo 3.1 Fast (via Runway)',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"values":[4,6,8]},"defaultDuration":8,"audio":true,
   "aspectRatios":{"ALL":[{"value":"1920:1080","label":"16:9 · 1080p"},{"value":"1080:1920","label":"9:16 · 1080p"},{"value":"1280:720","label":"16:9 · 720p"},{"value":"720:1280","label":"9:16 · 720p"}]}}',
 '{"unit":"CREDITS","rates":[{"perSecond":10,"audio":false},{"perSecond":15,"audio":true}]}', NULL, 40),
('RUNWAY', 'kling3.0_standard', 'Kling 3.0 Standard (via Runway)',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"min":3,"max":15},"defaultDuration":5,"audio":true,
   "aspectRatios":{"ALL":[{"value":"1280:720","label":"16:9 landscape"},{"value":"720:1280","label":"9:16 vertical"},{"value":"960:960","label":"1:1 square"}]}}',
 '{"unit":"CREDITS","rates":[{"perSecond":9,"audio":false},{"perSecond":13,"audio":true}]}', NULL, 50),

-- Google Gemini API (https://ai.google.dev/gemini-api/docs/omni) — replaces the Veo 3.1 preview models shut down on 22 Oct 2026
('GOOGLE_GEMINI', 'gemini-omni-1.1-flash', 'Gemini Omni Flash 1.1',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durationInPrompt":true,"durations":{"min":3,"max":10},"defaultDuration":8,
   "aspectRatios":{"ALL":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"}]},
   "resolutions":[{"value":"720p","label":"720p"},{"value":"1080p","label":"1080p (upscaled)"},{"value":"4k","label":"4K (upscaled)"},{"value":"360p","label":"360p draft"}]}',
 '{"unit":"USD","rates":[]}', 'Length is guided through the prompt; the API has no duration setting. Add your per-second price once Google publishes it on your billing page.', 60),

-- Kling direct API (https://kling.ai/document-api) — API-key standard, model in the path
('KLING', 'kling-4.0', 'Kling 4.0',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"min":3,"max":30},"defaultDuration":5,
   "aspectRatios":{"TEXT_TO_VIDEO":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"},{"value":"21:9","label":"21:9 cinematic"}]},
   "resolutions":[{"value":"1080p","label":"1080p"},{"value":"720p","label":"720p"}]}',
 '{"unit":"UNITS","rates":[]}', 'Billed from your Kling resource packs. Image-to-video keeps the image''s shape.', 70),
('KLING', 'kling-4.0-flash', 'Kling 4.0 Flash',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"min":3,"max":20},"defaultDuration":5,
   "aspectRatios":{"TEXT_TO_VIDEO":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"},{"value":"21:9","label":"21:9 cinematic"}]},
   "resolutions":[{"value":"720p","label":"720p"}]}',
 '{"unit":"UNITS","rates":[]}', NULL, 80),

-- Luma Dream Machine API (https://docs.lumalabs.ai) — credits are US cents
('LUMA', 'ray-2', 'Luma Ray 2',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"values":[5,9]},"defaultDuration":5,"publicImageUrl":true,
   "aspectRatios":{"ALL":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"},{"value":"21:9","label":"21:9 cinematic"},{"value":"9:21","label":"9:21"}]},
   "resolutions":[{"value":"720p","label":"720p"},{"value":"1080p","label":"1080p"},{"value":"540p","label":"540p"},{"value":"4k","label":"4K"}]}',
 '{"unit":"USD","rates":[]}', 'Image-to-video needs your studio online, because Luma downloads the image from a link.', 90),
('LUMA', 'ray-flash-2', 'Luma Ray Flash 2',
 '{"modes":["TEXT_TO_VIDEO","IMAGE_TO_VIDEO"],"durations":{"values":[5,9]},"defaultDuration":5,"publicImageUrl":true,
   "aspectRatios":{"ALL":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"},{"value":"21:9","label":"21:9 cinematic"},{"value":"9:21","label":"9:21"}]},
   "resolutions":[{"value":"720p","label":"720p"},{"value":"1080p","label":"1080p"},{"value":"540p","label":"540p"}]}',
 '{"unit":"USD","rates":[]}', NULL, 100);
