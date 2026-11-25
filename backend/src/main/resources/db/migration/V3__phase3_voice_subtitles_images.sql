-- Leenax AI Video Studio — Phase 3: voice & audio, subtitles, images, thumbnails

-- Models can now also be image and voice models.
ALTER TABLE ai_models DROP CONSTRAINT IF EXISTS ai_models_capability_check;
ALTER TABLE ai_models ADD CONSTRAINT ai_models_capability_check
    CHECK (capability IN ('VIDEO', 'TEXT', 'IMAGE', 'VOICE'));

-- ---------------------------------------------------------------------------
-- voice_profiles: reusable voices with your preferred settings.
-- Cloned voices keep a record of the consent given when they were created.
-- ---------------------------------------------------------------------------
CREATE TABLE voice_profiles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id         UUID         NOT NULL REFERENCES ai_accounts (id) ON DELETE CASCADE,
    provider           VARCHAR(30)  NOT NULL,
    provider_voice_id  VARCHAR(120) NOT NULL,
    name               VARCHAR(80)  NOT NULL,
    kind               VARCHAR(10)  NOT NULL CHECK (kind IN ('STOCK', 'CLONED')),
    language           VARCHAR(5),
    model_code         VARCHAR(120),
    settings           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    consent            JSONB,
    sample_media_ids   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_voice_profiles_account ON voice_profiles (account_id);

-- ---------------------------------------------------------------------------
-- pronunciation_rules: "say X as Y" replacements applied before synthesis,
-- e.g. brand names or Tamil words a voice keeps mispronouncing.
-- ---------------------------------------------------------------------------
CREATE TABLE pronunciation_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term         VARCHAR(120) NOT NULL,
    replacement  VARCHAR(240) NOT NULL,
    language     VARCHAR(5),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_pronunciation_term ON pronunciation_rules (lower(term), coalesce(language, ''));

-- ---------------------------------------------------------------------------
-- subtitle_tracks: timed captions for a video or audio file.
-- segments: [{"start": 0.0, "end": 2.4, "text": "..."}]
-- ---------------------------------------------------------------------------
CREATE TABLE subtitle_tracks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    media_id    UUID         NOT NULL REFERENCES media_assets (id) ON DELETE CASCADE,
    project_id  UUID         REFERENCES projects (id) ON DELETE SET NULL,
    language    VARCHAR(10)  NOT NULL,
    label       VARCHAR(120) NOT NULL,
    source      VARCHAR(12)  NOT NULL CHECK (source IN ('TRANSCRIBED', 'TRANSLATED', 'MANUAL')),
    segments    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_subtitle_tracks_media ON subtitle_tracks (media_id);

-- ---------------------------------------------------------------------------
-- thumbnail_designs: layered designs from the Thumbnail Studio (JSON document),
-- reopened and edited later. Exports become ordinary image media.
-- ---------------------------------------------------------------------------
CREATE TABLE thumbnail_designs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        UUID         REFERENCES projects (id) ON DELETE SET NULL,
    name              VARCHAR(160) NOT NULL,
    width             INTEGER      NOT NULL CHECK (width BETWEEN 64 AND 4096),
    height            INTEGER      NOT NULL CHECK (height BETWEEN 64 AND 4096),
    document          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_template       BOOLEAN      NOT NULL DEFAULT FALSE,
    export_media_id   UUID         REFERENCES media_assets (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_thumbnail_designs_updated ON thumbnail_designs (updated_at DESC);

-- ---------------------------------------------------------------------------
-- Seed image and voice models from the official docs (checked November 2026).
-- ---------------------------------------------------------------------------
INSERT INTO ai_models (provider, model_code, display_name, capability, options, pricing, notes, sort_order) VALUES
('OPENAI', 'gpt-image-2.5-flare', 'GPT Image 2.5 Flare', 'IMAGE',
 '{"sizes":[{"value":"1280x720","label":"16:9 · 1280×720 (YouTube thumbnail)"},{"value":"2560x1440","label":"16:9 · 2560×1440"},
            {"value":"1024x1024","label":"1:1 · 1024×1024"},{"value":"1024x1280","label":"4:5 · 1024×1280"},
            {"value":"720x1280","label":"9:16 · 720×1280"},{"value":"1440x2560","label":"9:16 · 1440×2560"}],
   "qualities":[{"value":"medium","label":"Medium"},{"value":"high","label":"High"},{"value":"low","label":"Low (draft)"}],
   "transparent":true,"references":true,"maxCount":4}',
 '{"unit":"USD","rates":[]}', 'OpenAI''s default image model. Good at readable text in images.', 200),
('OPENAI', 'gpt-image-2.5-sunburst', 'GPT Image 2.5 Sunburst', 'IMAGE',
 '{"sizes":[{"value":"1280x720","label":"16:9 · 1280×720 (YouTube thumbnail)"},{"value":"2560x1440","label":"16:9 · 2560×1440"},
            {"value":"1024x1024","label":"1:1 · 1024×1024"},{"value":"1024x1280","label":"4:5 · 1024×1280"},
            {"value":"720x1280","label":"9:16 · 720×1280"},{"value":"1440x2560","label":"9:16 · 1440×2560"}],
   "qualities":[{"value":"medium","label":"Medium"},{"value":"high","label":"High"},{"value":"low","label":"Low (draft)"}],
   "transparent":true,"references":true,"maxCount":4}',
 '{"unit":"USD","rates":[]}', NULL, 210),
('GOOGLE_GEMINI', 'gemini-nano-banana-2.1', 'Nano Banana 2.1', 'IMAGE',
 '{"aspectRatios":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},
                   {"value":"4:5","label":"4:5 portrait"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"}],
   "imageSizes":[{"value":"2K","label":"2K"},{"value":"1K","label":"1K"},{"value":"4K","label":"4K"}],
   "references":true,"maxCount":4}',
 '{"unit":"USD","rates":[]}', 'Google''s main image model. Accepts up to 14 reference images.', 220),
('GOOGLE_GEMINI', 'gemini-3.1-flash-lite-image', 'Nano Banana 2 Lite', 'IMAGE',
 '{"aspectRatios":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},
                   {"value":"4:5","label":"4:5 portrait"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"}],
   "imageSizes":[{"value":"1K","label":"1K"},{"value":"2K","label":"2K"}],"references":true,"maxCount":4}',
 '{"unit":"USD","rates":[]}', 'Fastest and cheapest Google image model.', 230),
('GOOGLE_GEMINI', 'gemini-3-pro-image', 'Nano Banana Pro', 'IMAGE',
 '{"aspectRatios":[{"value":"16:9","label":"16:9 landscape"},{"value":"9:16","label":"9:16 vertical"},{"value":"1:1","label":"1:1 square"},
                   {"value":"4:5","label":"4:5 portrait"},{"value":"4:3","label":"4:3"},{"value":"3:4","label":"3:4"}],
   "imageSizes":[{"value":"2K","label":"2K"},{"value":"1K","label":"1K"},{"value":"4K","label":"4K"}],"references":true,"maxCount":4}',
 '{"unit":"USD","rates":[]}', 'For complex, high-detail images.', 240),

('ELEVENLABS', 'eleven_v3', 'Eleven v3 (most expressive)', 'VOICE',
 '{"styleControl":"settings","languages":["ta","en"],"maxChars":4800,"cloning":true}',
 '{"unit":"CREDITS","rates":[]}', 'Supports Tamil. Up to 5,000 characters per request; longer scripts are split automatically.', 300),
('ELEVENLABS', 'eleven_multilingual_v2', 'Eleven Multilingual v2 (steady)', 'VOICE',
 '{"styleControl":"settings","languages":["ta","en"],"maxChars":9500,"cloning":true}',
 '{"unit":"CREDITS","rates":[]}', 'Supports Tamil. Very consistent long-form narration.', 310),
('ELEVENLABS', 'eleven_flash_v2_5', 'Eleven Flash v2.5 (fast, cheaper)', 'VOICE',
 '{"styleControl":"settings","languages":["ta","en"],"maxChars":30000,"cloning":true}',
 '{"unit":"CREDITS","rates":[]}', 'Supports Tamil. Quick drafts at lower cost.', 320),
('GOOGLE_GEMINI', 'gemini-3.8-flash-tts', 'Gemini 3.8 Flash TTS', 'VOICE',
 '{"styleControl":"prompt","languages":["ta","en"],"maxChars":3500,"cloning":false}',
 '{"unit":"USD","rates":[]}', 'Supports Tamil. Delivery is directed in words, e.g. “warm storytelling”.', 330),
('GOOGLE_GEMINI', 'gemini-3.8-flash-lite-tts', 'Gemini 3.8 Flash-Lite TTS', 'VOICE',
 '{"styleControl":"prompt","languages":["ta","en"],"maxChars":3500,"cloning":false}',
 '{"unit":"USD","rates":[]}', 'Supports Tamil. Faster and cheaper.', 340);
