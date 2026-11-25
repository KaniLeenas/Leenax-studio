-- Increment E11: automatic topic analysis.
--
-- When a topic list is approved, the AI analyses each topic: objectives, concepts,
-- misconceptions, a plan for the questions and a plan for the lesson video. The analysis is a
-- draft that a person approves, like everything else. Once it is approved, the studio carries
-- on by itself as far as the approval rules allow:
--   analysis approved                → notes at the chosen level are written
--   notes approved (and analysis)    → questions are written, following the analysis's plan
--   notes, QA and SEO approved       → the presentation (the lesson video's script) is made
-- Each result is again a draft for a person to approve. Nothing here approves anything, and
-- it only runs where an owner or editor turned it on with an AI account they chose.

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN
    ('SYLLABUS', 'TOPICS', 'TOPIC_ANALYSIS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES', 'LESSON_VIDEO', 'QUIZ_VIDEO'));
ALTER TABLE resources DROP CONSTRAINT ck_resources_shape;
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind IN ('NOTES', 'SLIDES', 'LESSON_VIDEO') AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind IN ('TOPIC_ANALYSIS', 'MCQ', 'QA', 'SEO', 'QUIZ_VIDEO') AND topic_id IS NOT NULL AND level IS NULL));

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check
    CHECK (kind IN ('SYLLABUS', 'TOPIC_ANALYSIS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES', 'NARRATION'));
-- which jobs the studio started by itself (shown as "started automatically")
ALTER TABLE analysis_jobs ADD COLUMN auto BOOLEAN NOT NULL DEFAULT false;

-- ---- the gate --------------------------------------------------------------------------------------

-- LX024: a topic analysis is approved only while its project's topic list is approved.
CREATE FUNCTION leenax_check_analysis_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r resources%ROWTYPE;
    n integer;
BEGIN
    IF NEW.status = 'APPROVED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT * INTO r FROM resources WHERE id = NEW.resource_id;
        IF r.kind = 'TOPIC_ANALYSIS' THEN
            SELECT count(*) INTO n FROM resource_versions v JOIN resources t ON t.id = v.resource_id
             WHERE t.project_id = r.project_id AND t.kind = 'TOPICS' AND v.status = 'APPROVED';
            IF n = 0 THEN
                RAISE EXCEPTION 'A topic analysis can be approved only while the topic list is approved' USING ERRCODE = 'LX024';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_resource_versions_analysis_gate BEFORE INSERT OR UPDATE OF status ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_check_analysis_gate();

-- ---- the settings ------------------------------------------------------------------------------------

CREATE TABLE topic_automation (
    project_id     UUID          PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    enabled        BOOLEAN       NOT NULL DEFAULT false,
    -- the AI account and model every automatic job uses (chosen by a person; paid per use)
    account_id     UUID          REFERENCES ai_accounts (id) ON DELETE SET NULL,
    model          VARCHAR(100),
    notes_level    VARCHAR(12)   NOT NULL DEFAULT 'INTERMEDIATE'
                   CHECK (notes_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'UNIVERSITY', 'EXAM')),
    mcq_count      INTEGER       NOT NULL DEFAULT 20 CHECK (mcq_count BETWEEN 4 AND 30),
    video_minutes  INTEGER       NOT NULL DEFAULT 8 CHECK (video_minutes BETWEEN 3 AND 20),
    -- what each step does by itself after an approval
    auto_notes     BOOLEAN       NOT NULL DEFAULT true,
    auto_mcq       BOOLEAN       NOT NULL DEFAULT true,
    auto_slides    BOOLEAN       NOT NULL DEFAULT true,
    updated_by     UUID          REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (NOT enabled OR (account_id IS NOT NULL AND model IS NOT NULL AND model <> ''))
);
ALTER TABLE topic_automation ENABLE ROW LEVEL SECURITY;
ALTER TABLE topic_automation FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON topic_automation FOR SELECT USING (leenax_can_read(project_id, NULL));
-- owners and editors (the people who start AI work) change it
CREATE POLICY add ON topic_automation FOR INSERT WITH CHECK (leenax_project_role(project_id) IN ('OWNER', 'EDITOR') OR leenax_mode() = 'admin');
CREATE POLICY change ON topic_automation FOR UPDATE USING (leenax_project_role(project_id) IN ('OWNER', 'EDITOR') OR leenax_mode() = 'admin');
REVOKE ALL ON topic_automation FROM leenax_app;
GRANT SELECT, INSERT, UPDATE ON topic_automation TO leenax_app;

-- ---- which topics are analysed -------------------------------------------------------------------------

-- The teaching units of a topic list: each selected topic under a chapter, and each selected
-- chapter that has no selected topics of its own. Subtopics belong to their topic.
CREATE FUNCTION leenax_analysis_topics(p_topics jsonb) RETURNS TABLE (topic_id text, title text)
    LANGUAGE sql IMMUTABLE AS $$
    SELECT c ->> 'id', c ->> 'title'
      FROM jsonb_array_elements(coalesce(p_topics -> 'nodes', '[]'::jsonb)) ch,
           jsonb_array_elements(coalesce(ch -> 'children', '[]'::jsonb)) c
     WHERE coalesce((ch ->> 'selected')::boolean, true) AND coalesce((c ->> 'selected')::boolean, true)
    UNION ALL
    SELECT ch ->> 'id', ch ->> 'title'
      FROM jsonb_array_elements(coalesce(p_topics -> 'nodes', '[]'::jsonb)) ch
     WHERE coalesce((ch ->> 'selected')::boolean, true)
       AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(ch -> 'children', '[]'::jsonb)) c
                        WHERE coalesce((c ->> 'selected')::boolean, true))
$$;

-- ---- carrying on after an approval -------------------------------------------------------------------

-- Queues one job unless the same work is already queued or running (the unique index).
CREATE FUNCTION leenax_auto_job(p_project uuid, p_kind text, p_target text, p_input jsonb) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
    INSERT INTO analysis_jobs (id, project_id, kind, target, input, auto, created_by)
    VALUES (gen_random_uuid(), p_project, p_kind, p_target, p_input, true, leenax_uid())
    ON CONFLICT DO NOTHING
$$;
REVOKE ALL ON FUNCTION leenax_auto_job(uuid, text, text, jsonb) FROM PUBLIC;

CREATE FUNCTION leenax_approved_content(p_project uuid, p_kind text, p_topic text, p_level text) RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
    SELECT v.content FROM resource_versions v JOIN resources r ON r.id = v.resource_id
     WHERE r.project_id = p_project AND r.kind = p_kind AND v.status = 'APPROVED'
       AND r.topic_id IS NOT DISTINCT FROM p_topic AND r.level IS NOT DISTINCT FROM p_level
     ORDER BY v.version_no DESC LIMIT 1
$$;
REVOKE ALL ON FUNCTION leenax_approved_content(uuid, text, text, text) FROM PUBLIC;

CREATE FUNCTION leenax_has_resource(p_project uuid, p_kind text, p_topic text, p_level text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
    SELECT EXISTS (SELECT 1 FROM resources r WHERE r.project_id = p_project AND r.kind = p_kind
                      AND r.topic_id IS NOT DISTINCT FROM p_topic AND r.level IS NOT DISTINCT FROM p_level)
$$;
REVOKE ALL ON FUNCTION leenax_has_resource(uuid, text, text, text) FROM PUBLIC;

-- The questions and presentation steps for one topic, each started once its inputs are approved
-- and only if nothing of that kind exists yet (a person's own work is never replaced).
CREATE FUNCTION leenax_auto_after_notes(p_project uuid, p_topic text, a topic_automation) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    plan jsonb;
    scenes integer;
BEGIN
    plan := leenax_approved_content(p_project, 'TOPIC_ANALYSIS', p_topic, NULL);
    IF plan IS NULL OR leenax_approved_content(p_project, 'NOTES', p_topic, a.notes_level) IS NULL THEN RETURN; END IF;
    IF a.auto_mcq AND NOT leenax_has_resource(p_project, 'MCQ', p_topic, NULL) THEN
        PERFORM leenax_auto_job(p_project, 'MCQ', p_topic, jsonb_build_object(
            'topicId', p_topic, 'notesLevel', a.notes_level, 'count', a.mcq_count, 'difficulties', '[]'::jsonb,
            'styles', '[]'::jsonb, 'accountId', a.account_id, 'model', a.model, 'allowAllNone', false));
    END IF;
    IF a.auto_slides AND NOT leenax_has_resource(p_project, 'SLIDES', p_topic, a.notes_level)
       AND leenax_approved_content(p_project, 'QA', p_topic, NULL) IS NOT NULL
       AND leenax_approved_content(p_project, 'SEO', p_topic, NULL) IS NOT NULL THEN
        scenes := coalesce(jsonb_array_length(plan -> 'videoPlan' -> 'scenes'), 8);
        PERFORM leenax_auto_job(p_project, 'SLIDES', p_topic || ':' || a.notes_level, jsonb_build_object(
            'topicId', p_topic, 'level', a.notes_level, 'accountId', a.account_id, 'model', a.model,
            'style', 'LECTURE', 'slides', least(40, greatest(6, scenes + 4))));
    END IF;
END $$;
REVOKE ALL ON FUNCTION leenax_auto_after_notes(uuid, text, topic_automation) FROM PUBLIC;

CREATE FUNCTION leenax_topic_automation() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    r  resources%ROWTYPE;
    a  topic_automation%ROWTYPE;
    t  record;
BEGIN
    -- a status change to approved, or a version inserted approved (a reviewer's approval with edits)
    IF NEW.status <> 'APPROVED' OR (TG_OP = 'UPDATE' AND OLD.status = 'APPROVED') THEN RETURN NULL; END IF;
    SELECT * INTO r FROM resources WHERE id = NEW.resource_id;
    SELECT * INTO a FROM topic_automation WHERE project_id = r.project_id AND enabled;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF r.kind = 'TOPICS' THEN
        FOR t IN SELECT * FROM leenax_analysis_topics(NEW.content) LOOP
            CONTINUE WHEN t.topic_id IS NULL OR leenax_has_resource(r.project_id, 'TOPIC_ANALYSIS', t.topic_id, NULL);
            PERFORM leenax_auto_job(r.project_id, 'TOPIC_ANALYSIS', t.topic_id, jsonb_build_object(
                'topicId', t.topic_id, 'accountId', a.account_id, 'model', a.model, 'mcqCount', a.mcq_count,
                'videoMinutes', a.video_minutes, 'level', a.notes_level));
        END LOOP;
    ELSIF r.kind = 'TOPIC_ANALYSIS' THEN
        IF a.auto_notes AND NOT leenax_has_resource(r.project_id, 'NOTES', r.topic_id, a.notes_level) THEN
            PERFORM leenax_auto_job(r.project_id, 'NOTES', r.topic_id || ':' || a.notes_level, jsonb_build_object(
                'topicId', r.topic_id, 'level', a.notes_level, 'accountId', a.account_id, 'model', a.model));
        END IF;
        PERFORM leenax_auto_after_notes(r.project_id, r.topic_id, a);
    ELSIF r.kind = 'NOTES' AND r.level = a.notes_level OR r.kind IN ('QA', 'SEO') THEN
        PERFORM leenax_auto_after_notes(r.project_id, r.topic_id, a);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_resource_versions_automation AFTER INSERT OR UPDATE OF status ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_topic_automation();

-- ---- notifications: say what an analysis job was, and where it opens ------------------------------------

CREATE OR REPLACE FUNCTION leenax_resource_link(p_kind text, p_project uuid, p_resource uuid) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_kind
        WHEN 'SYLLABUS' THEN '/projects/' || p_project || '/syllabus'
        WHEN 'TOPICS' THEN '/projects/' || p_project || '/topics'
        WHEN 'TOPIC_ANALYSIS' THEN '/topic-analysis/' || p_resource
        WHEN 'NOTES' THEN '/notes/' || p_resource
        WHEN 'MCQ' THEN '/mcq/' || p_resource
        WHEN 'QA' THEN '/qa-report/' || p_resource
        WHEN 'SEO' THEN '/seo/' || p_resource
        WHEN 'SLIDES' THEN '/slides/' || p_resource
        WHEN 'LESSON_VIDEO' THEN '/videos/' || p_resource
        WHEN 'QUIZ_VIDEO' THEN '/videos/' || p_resource
        ELSE '/projects/' || p_project END
$$;

CREATE OR REPLACE FUNCTION leenax_notify_job() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    what  text;
    link  text;
    pname text;
BEGIN
    IF NEW.status = OLD.status OR NEW.status NOT IN ('SUCCEEDED', 'FAILED') THEN RETURN NULL; END IF;
    SELECT p.name INTO pname FROM projects p WHERE p.id = NEW.project_id;
    what := CASE NEW.kind WHEN 'SYLLABUS' THEN 'The syllabus report' WHEN 'TOPIC_ANALYSIS' THEN 'The topic analysis'
                          WHEN 'NOTES' THEN 'The notes' WHEN 'MCQ' THEN 'The questions'
                          WHEN 'QA' THEN 'The academic QA check' WHEN 'SEO' THEN 'The SEO research' WHEN 'SLIDES' THEN 'The presentation'
                          WHEN 'NARRATION' THEN 'The narration' ELSE 'The job' END
            || CASE WHEN NEW.auto THEN ' (started automatically)' ELSE '' END;
    link := CASE NEW.kind WHEN 'SYLLABUS' THEN '/projects/' || NEW.project_id || '/syllabus'
                          WHEN 'TOPIC_ANALYSIS' THEN '/projects/' || NEW.project_id || '/analysis'
                          WHEN 'NOTES' THEN '/projects/' || NEW.project_id || '/materials'
                          WHEN 'MCQ' THEN '/projects/' || NEW.project_id || '/materials'
                          WHEN 'QA' THEN '/projects/' || NEW.project_id || '/qa'
                          WHEN 'SEO' THEN '/projects/' || NEW.project_id || '/seo'
                          WHEN 'SLIDES' THEN '/projects/' || NEW.project_id || '/slides'
                          WHEN 'NARRATION' THEN '/videos/' || NEW.target
                          ELSE '/projects/' || NEW.project_id END;
    PERFORM leenax_notify(NEW.created_by, NEW.project_id, CASE NEW.status WHEN 'SUCCEEDED' THEN 'JOB_DONE' ELSE 'JOB_FAILED' END,
        what || CASE NEW.status WHEN 'SUCCEEDED' THEN ' is ready' ELSE ' failed' END
             || CASE WHEN NEW.summary IS NOT NULL AND NEW.status = 'SUCCEEDED' THEN ': ' || NEW.summary ELSE '' END,
        CASE WHEN NEW.status = 'FAILED' THEN coalesce(NEW.error, 'No reason was given') || ' (' || coalesce(pname, '') || ')' ELSE pname END,
        link);
    RETURN NULL;
END $$;

-- Fix (E10): background jobs run with the starter's id as leenax.user_id, so "not for what you
-- did yourself" also silenced job, render and post notifications. Only a person's own action,
-- in user or admin mode, is skipped now.
CREATE OR REPLACE FUNCTION leenax_notify(p_user uuid, p_project uuid, p_kind text, p_title text, p_body text, p_link text)
    RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
    INSERT INTO notifications (user_id, project_id, kind, title, body, link)
    SELECT p_user, p_project, p_kind, left(p_title, 300), left(p_body, 600), left(p_link, 300)
     WHERE p_user IS NOT NULL
       AND NOT (p_user IS NOT DISTINCT FROM leenax_uid() AND coalesce(leenax_mode(), '') IN ('user', 'admin'))
       AND EXISTS (SELECT 1 FROM users u WHERE u.id = p_user AND u.status = 'ACTIVE')
$$;
