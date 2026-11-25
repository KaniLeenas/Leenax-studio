-- Increment E4: educational notes and the MCQ engine.
--
-- Notes and MCQ banks are approvable resources like the syllabus (V11), but there
-- are many per project: notes per topic and level, a question bank per topic.
-- What depends on what (resource_links): notes on the topic list, a bank on the
-- notes it was written from. Editing approved notes marks their bank Outdated.

-- ---- resources per topic -------------------------------------------------------------------------

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN ('SYLLABUS', 'TOPICS', 'NOTES', 'MCQ'));
-- The topic (an id from the topic list) and, for notes, the level.
ALTER TABLE resources ADD COLUMN topic_id VARCHAR(40);
ALTER TABLE resources ADD COLUMN level VARCHAR(20)
    CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'UNIVERSITY', 'EXAM'));
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind = 'NOTES' AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind = 'MCQ' AND topic_id IS NOT NULL AND level IS NULL));
DROP INDEX uq_resources_one_per_project;
CREATE UNIQUE INDEX uq_resources_identity ON resources (project_id, kind, coalesce(topic_id, ''), coalesce(level, ''));

-- ---- jobs for notes and questions ----------------------------------------------------------------

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check CHECK (kind IN ('SYLLABUS', 'NOTES', 'MCQ'));
-- What the job writes: '' for the syllabus, the topic and level for notes, the topic for a bank.
ALTER TABLE analysis_jobs ADD COLUMN target VARCHAR(80) NOT NULL DEFAULT '';
DROP INDEX uq_analysis_one_running;
CREATE UNIQUE INDEX uq_analysis_one_running ON analysis_jobs (project_id, kind, target) WHERE status IN ('QUEUED', 'RUNNING');
-- What a finished job did, in a line (for example, how many generated questions were kept).
ALTER TABLE analysis_jobs ADD COLUMN summary VARCHAR(500);

-- ---- comments on versions ------------------------------------------------------------------------

-- Reviewers and editors comment on a version, optionally on a quoted passage. Comments
-- sit beside the content, so commenting never changes a version (or its approval).
CREATE TABLE review_comments (
    id          UUID          PRIMARY KEY,
    project_id  UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id UUID          NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_id  UUID          NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    quote       VARCHAR(500),
    body        VARCHAR(2000) NOT NULL CHECK (length(btrim(body)) > 0),
    author_id   UUID          REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID          REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX idx_review_comments_resource ON review_comments (resource_id, created_at);

-- ---- rendered equations and diagrams -------------------------------------------------------------

-- The browser draws equations (MathJax) and diagrams (Mermaid) as SVG. The drawing is
-- kept per project, by a hash of its source, for exports; it never changes a version.
CREATE TABLE note_figures (
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind        VARCHAR(10) NOT NULL CHECK (kind IN ('MATH', 'MERMAID')),
    source_hash CHAR(64)    NOT NULL,
    svg         TEXT        NOT NULL CHECK (length(svg) <= 600000),
    created_by  UUID        REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, kind, source_hash)
);

-- ---- randomised quizzes --------------------------------------------------------------------------

-- A quiz is drawn from approved question banks with a recorded seed, and keeps a copy
-- of its questions, so it exports the same way later even if the banks change.
CREATE TABLE quizzes (
    id         UUID         PRIMARY KEY,
    project_id UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title      VARCHAR(200) NOT NULL,
    seed       BIGINT       NOT NULL,
    config     JSONB        NOT NULL,
    questions  JSONB        NOT NULL,
    created_by UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_quizzes_project ON quizzes (project_id, created_at DESC);

-- ---- row-level security ------------------------------------------------------------------------

ALTER TABLE review_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_comments FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON review_comments FOR SELECT
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY add ON review_comments FOR INSERT
    WITH CHECK (author_id = leenax_uid()
                AND EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND r.project_id = review_comments.project_id
                            AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));
-- Resolving: editors and reviewers. The text of a comment never changes (see trigger).
CREATE POLICY change ON review_comments FOR UPDATE
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id
                   AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))))
    WITH CHECK (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id
                   AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));
-- Authors may delete their own comment while it is open.
CREATE POLICY remove ON review_comments FOR DELETE USING (author_id = leenax_uid() AND resolved_at IS NULL);

CREATE FUNCTION leenax_check_comment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.body <> OLD.body OR NEW.quote IS DISTINCT FROM OLD.quote OR NEW.author_id IS DISTINCT FROM OLD.author_id
       OR NEW.version_id <> OLD.version_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'A comment can be resolved or reopened, not rewritten' USING ERRCODE = 'LX013';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_review_comments_rules BEFORE UPDATE ON review_comments
    FOR EACH ROW EXECUTE FUNCTION leenax_check_comment();

ALTER TABLE note_figures ENABLE ROW LEVEL SECURITY;
ALTER TABLE note_figures FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON note_figures FOR SELECT USING (leenax_can_read(project_id, NULL));
CREATE POLICY add ON note_figures FOR INSERT
    WITH CHECK (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id));

ALTER TABLE quizzes ENABLE ROW LEVEL SECURITY;
ALTER TABLE quizzes FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON quizzes FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON quizzes FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));
CREATE POLICY remove ON quizzes FOR DELETE USING (leenax_can_write(project_id, created_by));

GRANT SELECT, INSERT, UPDATE, DELETE ON review_comments, note_figures, quizzes TO leenax_app;
