-- E10: in-app notifications, written by the database when something happens that a person
-- should know about, so no code path can forget one:
--   * a version is sent for review          → the project's owners and reviewers (not the sender)
--   * a version is approved, sent back,
--     rejected or made outdated             → its author (not the person who did it)
--   * an AI job, a render or a post
--     finishes or fails                     → the person who started it
-- Each person sees and marks only their own.

CREATE TABLE notifications (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id  UUID          REFERENCES projects (id) ON DELETE CASCADE,
    kind        VARCHAR(24)   NOT NULL CHECK (kind IN ('REVIEW_REQUESTED', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED', 'OUTDATED',
                                                       'JOB_DONE', 'JOB_FAILED', 'RENDER_DONE', 'RENDER_FAILED', 'PUBLISHED', 'PUBLISH_FAILED')),
    title       VARCHAR(300)  NOT NULL,
    body        VARCHAR(600),
    link        VARCHAR(300),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY own ON notifications FOR SELECT USING (user_id = leenax_uid());
CREATE POLICY mark ON notifications FOR UPDATE USING (user_id = leenax_uid()) WITH CHECK (user_id = leenax_uid());
CREATE POLICY clear ON notifications FOR DELETE USING (user_id = leenax_uid());
-- the default grants (V9) give the app every table; here it may only read, mark read and clear
REVOKE INSERT, UPDATE ON notifications FROM leenax_app;
GRANT SELECT, DELETE ON notifications TO leenax_app;
GRANT UPDATE (read_at) ON notifications TO leenax_app;
-- authorisation records (V17) are only ever added; the trigger already refuses changes, and
-- now the grants say so too
REVOKE UPDATE, DELETE ON publish_authorisations FROM leenax_app;

-- Only these functions write notifications (as the table owner, past row-level security).
CREATE FUNCTION leenax_notify(p_user uuid, p_project uuid, p_kind text, p_title text, p_body text, p_link text)
    RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
    INSERT INTO notifications (user_id, project_id, kind, title, body, link)
    SELECT p_user, p_project, p_kind, left(p_title, 300), left(p_body, 600), left(p_link, 300)
     WHERE p_user IS NOT NULL
       AND p_user IS DISTINCT FROM leenax_uid()                      -- not for what you did yourself
       AND EXISTS (SELECT 1 FROM users u WHERE u.id = p_user AND u.status = 'ACTIVE')
$$;
REVOKE ALL ON FUNCTION leenax_notify(uuid, uuid, text, text, text, text) FROM PUBLIC;

-- Where a resource opens in the app.
CREATE FUNCTION leenax_resource_link(p_kind text, p_project uuid, p_resource uuid) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_kind
        WHEN 'SYLLABUS' THEN '/projects/' || p_project || '/syllabus'
        WHEN 'TOPICS' THEN '/projects/' || p_project || '/topics'
        WHEN 'NOTES' THEN '/notes/' || p_resource
        WHEN 'MCQ' THEN '/mcq/' || p_resource
        WHEN 'QA' THEN '/qa-report/' || p_resource
        WHEN 'SEO' THEN '/seo/' || p_resource
        WHEN 'SLIDES' THEN '/slides/' || p_resource
        WHEN 'LESSON_VIDEO' THEN '/videos/' || p_resource
        WHEN 'QUIZ_VIDEO' THEN '/videos/' || p_resource
        ELSE '/projects/' || p_project END
$$;

-- ---- approvals -----------------------------------------------------------------------------------

CREATE FUNCTION leenax_notify_review() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    r       record;
    author  uuid;
    pname   text;
    who     text;
    m       record;
    note    text;
BEGIN
    SELECT x.kind, x.title INTO r FROM resources x WHERE x.id = NEW.resource_id;
    SELECT p.name INTO pname FROM projects p WHERE p.id = NEW.project_id;
    SELECT v.created_by INTO author FROM resource_versions v WHERE v.id = NEW.version_id;
    SELECT u.display_name INTO who FROM users u WHERE u.id = NEW.actor_id;
    note := CASE WHEN NEW.comment IS NULL OR NEW.comment = '' THEN pname ELSE pname || ': “' || left(NEW.comment, 400) || '”' END;
    IF NEW.action = 'SUBMITTED' THEN
        FOR m IN SELECT pm.user_id FROM project_members pm WHERE pm.project_id = NEW.project_id AND pm.role IN ('OWNER', 'REVIEWER') LOOP
            CONTINUE WHEN m.user_id = author;                          -- the author can't review their own work
            PERFORM leenax_notify(m.user_id, NEW.project_id, 'REVIEW_REQUESTED',
                coalesce(who, 'Someone') || ' asked you to review “' || r.title || '”', pname,
                leenax_resource_link(r.kind, NEW.project_id, NEW.resource_id));
        END LOOP;
    ELSIF NEW.action IN ('APPROVED', 'APPROVED_WITH_EDITS', 'CHANGES_REQUESTED', 'REJECTED', 'OUTDATED') THEN
        PERFORM leenax_notify(author, NEW.project_id,
            CASE NEW.action WHEN 'APPROVED_WITH_EDITS' THEN 'APPROVED' ELSE NEW.action END,
            CASE NEW.action
                WHEN 'APPROVED' THEN '“' || r.title || '” was approved'
                WHEN 'APPROVED_WITH_EDITS' THEN '“' || r.title || '” was approved with edits'
                WHEN 'CHANGES_REQUESTED' THEN 'Changes were asked for on “' || r.title || '”'
                WHEN 'REJECTED' THEN '“' || r.title || '” was rejected'
                ELSE '“' || r.title || '” is outdated: what it was made from changed' END
              || CASE WHEN who IS NOT NULL AND NEW.action <> 'OUTDATED' THEN ' by ' || who ELSE '' END,
            note, leenax_resource_link(r.kind, NEW.project_id, NEW.resource_id));
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER trg_review_events_notify AFTER INSERT ON review_events FOR EACH ROW EXECUTE FUNCTION leenax_notify_review();

-- ---- AI jobs ---------------------------------------------------------------------------------------

CREATE FUNCTION leenax_notify_job() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    what  text;
    link  text;
    pname text;
BEGIN
    IF NEW.status = OLD.status OR NEW.status NOT IN ('SUCCEEDED', 'FAILED') THEN RETURN NULL; END IF;
    SELECT p.name INTO pname FROM projects p WHERE p.id = NEW.project_id;
    what := CASE NEW.kind WHEN 'SYLLABUS' THEN 'The syllabus report' WHEN 'NOTES' THEN 'The notes' WHEN 'MCQ' THEN 'The questions'
                          WHEN 'QA' THEN 'The academic QA check' WHEN 'SEO' THEN 'The SEO research' WHEN 'SLIDES' THEN 'The presentation'
                          WHEN 'NARRATION' THEN 'The narration' ELSE 'The job' END;
    link := CASE NEW.kind WHEN 'SYLLABUS' THEN '/projects/' || NEW.project_id || '/syllabus'
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
CREATE TRIGGER trg_analysis_jobs_notify AFTER UPDATE OF status ON analysis_jobs FOR EACH ROW EXECUTE FUNCTION leenax_notify_job();

CREATE FUNCTION leenax_notify_render() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    video uuid;
BEGIN
    IF NEW.status = OLD.status OR NEW.status NOT IN ('COMPLETED', 'FAILED') THEN RETURN NULL; END IF;
    SELECT vr.resource_id INTO video FROM video_renders vr WHERE vr.render_job_id = NEW.id;
    PERFORM leenax_notify(NEW.created_by, NEW.project_id, CASE NEW.status WHEN 'COMPLETED' THEN 'RENDER_DONE' ELSE 'RENDER_FAILED' END,
        CASE NEW.status WHEN 'COMPLETED' THEN 'The render of “' || NEW.timeline_name || '” is ready'
                        ELSE 'The render of “' || NEW.timeline_name || '” failed' END,
        CASE WHEN NEW.status = 'FAILED' THEN NEW.error_message END,
        CASE WHEN video IS NOT NULL THEN '/videos/' || video ELSE '/edit/timeline/' || NEW.timeline_id END);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_render_jobs_notify AFTER UPDATE OF status ON render_jobs FOR EACH ROW EXECUTE FUNCTION leenax_notify_render();

-- ---- publishing --------------------------------------------------------------------------------------

CREATE FUNCTION leenax_notify_post() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    p record;
    pub record;
BEGIN
    IF NEW.status = OLD.status OR NEW.status NOT IN ('PUBLISHED', 'FAILED') THEN RETURN NULL; END IF;
    SELECT x.title, x.created_by INTO p FROM posts x WHERE x.id = NEW.post_id;
    SELECT y.project_id, y.resource_id INTO pub FROM youtube_publications y WHERE y.post_target_id = NEW.id;
    PERFORM leenax_notify(p.created_by, pub.project_id, CASE NEW.status WHEN 'PUBLISHED' THEN 'PUBLISHED' ELSE 'PUBLISH_FAILED' END,
        CASE NEW.status WHEN 'PUBLISHED' THEN '“' || p.title || '” is on ' || CASE NEW.platform WHEN 'YOUTUBE' THEN 'YouTube' WHEN 'TIKTOK' THEN 'TikTok' ELSE initcap(NEW.platform) END || ' (' || NEW.account_name || ')'
                        ELSE 'Posting “' || p.title || '” to ' || NEW.account_name || ' failed' END,
        CASE WHEN NEW.status = 'FAILED' THEN NEW.error_message ELSE NEW.warning END,
        CASE WHEN NEW.status = 'PUBLISHED' AND NEW.remote_url IS NOT NULL THEN NEW.remote_url
             WHEN pub.project_id IS NOT NULL THEN '/projects/' || pub.project_id || '/youtube'
             ELSE '/publish/calendar' END);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_post_targets_notify AFTER UPDATE OF status ON post_targets FOR EACH ROW EXECUTE FUNCTION leenax_notify_post();
