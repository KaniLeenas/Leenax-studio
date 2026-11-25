-- Leenax AI Video Studio — Increment 8: scripts, storyboards and characters

-- ---------------------------------------------------------------------------
-- scripts: ideas and scripts, versioned (a rewrite is a new row whose
-- parent_id points at the version it came from).
-- ---------------------------------------------------------------------------
CREATE TABLE scripts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID         REFERENCES projects (id) ON DELETE SET NULL,
    parent_id    UUID         REFERENCES scripts (id) ON DELETE SET NULL,
    title        VARCHAR(200) NOT NULL,
    language     VARCHAR(5)   NOT NULL CHECK (language IN ('EN', 'TA')),
    video_type   VARCHAR(20)  NOT NULL,
    idea         TEXT,
    body         TEXT         NOT NULL DEFAULT '',
    -- scene-by-scene structure when the AI wrote it: {"intro": "...", "scenes": [{"heading","narration","visual"}], "outro": "..."}
    sections     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- per-platform title, description, caption and hashtags
    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version_no   INTEGER      NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_scripts_project ON scripts (project_id, updated_at DESC);
CREATE INDEX idx_scripts_parent ON scripts (parent_id);

-- ---------------------------------------------------------------------------
-- storyboard_characters: reusable characters. project_id NULL = available in
-- every project.
-- ---------------------------------------------------------------------------
CREATE TABLE storyboard_characters (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        UUID          REFERENCES projects (id) ON DELETE SET NULL,
    name              VARCHAR(120)  NOT NULL,
    description       TEXT,
    appearance        TEXT,
    clothing          TEXT,
    voice_profile_id  UUID          REFERENCES voice_profiles (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_characters_project ON storyboard_characters (project_id);

-- ---------------------------------------------------------------------------
-- storyboard_projects: a storyboard, optionally made from a script.
-- ---------------------------------------------------------------------------
CREATE TABLE storyboard_projects (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          UUID          REFERENCES projects (id) ON DELETE SET NULL,
    script_id           UUID          REFERENCES scripts (id) ON DELETE SET NULL,
    title               VARCHAR(200)  NOT NULL,
    aspect_ratio        VARCHAR(10)   NOT NULL CHECK (aspect_ratio IN ('LANDSCAPE', 'PORTRAIT', 'SQUARE')),
    language            VARCHAR(5)    NOT NULL CHECK (language IN ('EN', 'TA')),
    style               TEXT,
    narration_media_id  UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    -- [{"sceneId": "...", "start": 0.0, "length": 4.2}] matching the narration file
    narration_parts     JSONB         NOT NULL DEFAULT '[]'::jsonb,
    timeline_id         UUID          REFERENCES timelines (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_storyboards_project ON storyboard_projects (project_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- storyboard_scenes: one shot each, linked to its image, its generated video
-- (through the generation job) and the characters in it.
-- ---------------------------------------------------------------------------
CREATE TABLE storyboard_scenes (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storyboard_id     UUID          NOT NULL REFERENCES storyboard_projects (id) ON DELETE CASCADE,
    position          INTEGER       NOT NULL,
    title             VARCHAR(160)  NOT NULL DEFAULT '',
    description       TEXT          NOT NULL DEFAULT '',
    narration         TEXT          NOT NULL DEFAULT '',
    dialogue          JSONB         NOT NULL DEFAULT '[]'::jsonb,
    duration_seconds  NUMERIC(5,1)  NOT NULL DEFAULT 5,
    camera_angle      VARCHAR(40),
    camera_movement   VARCHAR(40),
    lighting          VARCHAR(300),
    composition       VARCHAR(300),
    sound_notes       TEXT,
    character_ids     JSONB         NOT NULL DEFAULT '[]'::jsonb,
    image_prompt      TEXT,
    video_prompt      TEXT,
    image_media_id    UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    video_media_id    UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    video_job_id      UUID          REFERENCES generation_jobs (id) ON DELETE SET NULL,
    -- the scene's dialogue spoken in each character's voice
    dialogue_media_id UUID          REFERENCES media_assets (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_scenes_storyboard ON storyboard_scenes (storyboard_id, position);

-- ---------------------------------------------------------------------------
-- storyboard_references: reference images for a character or for one scene.
-- ---------------------------------------------------------------------------
CREATE TABLE storyboard_references (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    character_id  UUID         REFERENCES storyboard_characters (id) ON DELETE CASCADE,
    scene_id      UUID         REFERENCES storyboard_scenes (id) ON DELETE CASCADE,
    media_id      UUID         NOT NULL REFERENCES media_assets (id) ON DELETE CASCADE,
    label         VARCHAR(60),
    position      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK ((character_id IS NULL) <> (scene_id IS NULL))
);
CREATE INDEX idx_references_character ON storyboard_references (character_id, position);
CREATE INDEX idx_references_scene ON storyboard_references (scene_id, position);
