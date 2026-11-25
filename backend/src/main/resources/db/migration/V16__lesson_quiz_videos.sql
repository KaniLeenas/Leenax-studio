-- Increment E8: lesson and quiz videos.
--
-- A lesson video is made from an approved presentation (per topic and notes level); a quiz
-- video from a topic's approved MCQ set, once its SEO report is approved. Each is an
-- approvable resource whose content is the video's plan: scenes, narration text and voice.
-- The video itself is an ordinary editor timeline rendered by the render service; every
-- render is checked with ffprobe, and a video version can only be approved while a render of
-- exactly that version passed the checks.

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN
    ('SYLLABUS', 'TOPICS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES', 'LESSON_VIDEO', 'QUIZ_VIDEO'));
ALTER TABLE resources DROP CONSTRAINT ck_resources_shape;
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind IN ('NOTES', 'SLIDES', 'LESSON_VIDEO') AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind IN ('MCQ', 'QA', 'SEO', 'QUIZ_VIDEO') AND topic_id IS NOT NULL AND level IS NULL));

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check CHECK (kind IN ('SYLLABUS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES', 'NARRATION'));

-- What ffprobe found in a finished render: {ok, checkedAt, expected {...}, found {...}, problems[]}.
-- A render that fails its checks is marked failed, not completed.
ALTER TABLE render_jobs ADD COLUMN validation JSONB;

-- Which render belongs to which video version (by the exact content rendered).
CREATE TABLE video_renders (
    id             UUID         PRIMARY KEY,
    project_id     UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id    UUID         NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_id     UUID         NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    content_hash   CHAR(64)     NOT NULL,
    render_job_id  UUID         NOT NULL UNIQUE REFERENCES render_jobs (id) ON DELETE CASCADE,
    -- the editor timeline the video is drawn on (one per video, updated for each render)
    timeline_id    UUID         REFERENCES timelines (id) ON DELETE SET NULL,
    created_by     UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_video_renders_resource ON video_renders (resource_id, created_at DESC);

ALTER TABLE video_renders ENABLE ROW LEVEL SECURITY;
ALTER TABLE video_renders FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON video_renders FOR SELECT USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY add ON video_renders FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND r.project_id = video_renders.project_id
            AND leenax_can_write(r.project_id, r.created_by)));
GRANT SELECT, INSERT ON video_renders TO leenax_app;

-- A video version can only become Approved when
--   LX018: what it is made from is approved (the presentation; or the MCQ set and the SEO report), and
--   LX019: a render of exactly this content finished and passed its file checks.
CREATE FUNCTION leenax_check_video_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v      resources%ROWTYPE;
    n      integer;
BEGIN
    IF NEW.status = 'APPROVED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT * INTO v FROM resources WHERE id = NEW.resource_id;
        IF v.kind = 'LESSON_VIDEO' THEN
            SELECT count(*) INTO n FROM resource_versions x JOIN resources r ON r.id = x.resource_id
             WHERE r.project_id = v.project_id AND r.kind = 'SLIDES' AND r.topic_id = v.topic_id AND r.level = v.level
               AND x.status = 'APPROVED';
            IF n = 0 THEN
                RAISE EXCEPTION 'A lesson video needs an approved presentation' USING ERRCODE = 'LX018';
            END IF;
        ELSIF v.kind = 'QUIZ_VIDEO' THEN
            SELECT count(*) INTO n FROM resource_versions x JOIN resources r ON r.id = x.resource_id
             WHERE r.project_id = v.project_id AND r.topic_id = v.topic_id AND x.status = 'APPROVED'
               AND r.kind IN ('MCQ', 'SEO')
             GROUP BY r.project_id HAVING count(DISTINCT r.kind) = 2;
            IF n IS NULL OR n = 0 THEN
                RAISE EXCEPTION 'A quiz video needs an approved MCQ set and an approved SEO report' USING ERRCODE = 'LX018';
            END IF;
        ELSE
            RETURN NEW;
        END IF;
        SELECT count(*) INTO n FROM video_renders vr JOIN render_jobs j ON j.id = vr.render_job_id
         WHERE vr.resource_id = NEW.resource_id AND vr.content_hash = NEW.content_hash
           AND j.status = 'COMPLETED' AND j.validation ->> 'ok' = 'true' AND j.output_media_id IS NOT NULL;
        IF n = 0 THEN
            RAISE EXCEPTION 'This version has no render that passed the file checks' USING ERRCODE = 'LX019';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_resource_versions_video_gate BEFORE INSERT OR UPDATE ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_check_video_gate();

-- Google Cloud Text-to-Speech: one entry; the voice chosen decides the type and price.
INSERT INTO ai_models (provider, model_code, display_name, capability, options, pricing, notes, sort_order) VALUES
('GOOGLE_CLOUD_TTS', 'cloud-tts', 'Google Cloud Text-to-Speech', 'VOICE',
 '{"styleControl":"none","languages":["ta","en"],"maxChars":1400,"cloning":false}',
 '{"unit":"USD","rates":[]}',
 'Tamil and English voices. The voice decides the type and price (list prices, November 2026, per million characters): Standard and WaveNet US$4 (4 million free a month), Neural2 US$16, Chirp 3: HD US$30 (1 million free a month).',
 350);
