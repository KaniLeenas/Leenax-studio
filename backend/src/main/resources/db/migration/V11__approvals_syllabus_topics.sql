-- Increment E3: the approval engine, the syllabus and the topic list.
--
-- A resource (for now: a project's syllabus, and its topic list) has numbered versions.
-- Each version has a review status:
--   DRAFT -> IN_REVIEW -> APPROVED | CHANGES_REQUESTED | REJECTED
--   and OUTDATED (something it depends on changed) or ARCHIVED (replaced).
-- A decision always names one exact version, and is recorded in review_events with
-- who, when, the status before and after, and the comment.
--
-- Rules held here as well as in the app:
--   * a version's content can't change once it has left DRAFT;
--   * at most one approved version per resource;
--   * only a person with a reviewing role decides (never a background job), and when the project's
--     "reviewer must not be the author" setting is on, not the version's author;
--   * the decision record can't be edited or deleted.

CREATE FUNCTION leenax_can_review(p uuid) RETURNS boolean LANGUAGE sql STABLE AS
$$ SELECT leenax_is_super() OR coalesce(leenax_project_role(p) IN ('OWNER', 'REVIEWER'), false) $$;

CREATE TABLE resources (
    id         UUID         PRIMARY KEY,
    project_id UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind       VARCHAR(20)  NOT NULL CHECK (kind IN ('SYLLABUS', 'TOPICS')),
    title      VARCHAR(200) NOT NULL,
    created_by UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- One syllabus and one topic list per project.
CREATE UNIQUE INDEX uq_resources_one_per_project ON resources (project_id, kind);

CREATE TABLE resource_versions (
    id                    UUID        PRIMARY KEY,
    resource_id           UUID        NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_no            INT         NOT NULL CHECK (version_no > 0),
    status                VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                          CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'OUTDATED', 'ARCHIVED')),
    -- How it was made: by the AI analysis, by hand, as an edit of an earlier version,
    -- or by a reviewer who approved with edits.
    origin                VARCHAR(10) NOT NULL CHECK (origin IN ('AI', 'MANUAL', 'EDIT', 'REVIEWER')),
    content               JSONB       NOT NULL,
    content_hash          CHAR(64)    NOT NULL,
    based_on_version_id   UUID        REFERENCES resource_versions (id) ON DELETE SET NULL,
    -- For a topic list: the syllabus version it was built from.
    depends_on_version_id UUID        REFERENCES resource_versions (id) ON DELETE SET NULL,
    created_by            UUID        REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_resource_version UNIQUE (resource_id, version_no)
);
CREATE UNIQUE INDEX uq_resource_one_approved ON resource_versions (resource_id) WHERE status = 'APPROVED';
CREATE INDEX idx_resource_versions_status ON resource_versions (resource_id, status);

-- What depends on what: a topic list depends on its project's syllabus.
CREATE TABLE resource_links (
    resource_id   UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    depends_on_id UUID NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    PRIMARY KEY (resource_id, depends_on_id),
    CHECK (resource_id <> depends_on_id)
);

CREATE TABLE review_events (
    id              UUID          PRIMARY KEY,
    project_id      UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id     UUID          NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_id      UUID          NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    action          VARCHAR(20)   NOT NULL CHECK (action IN ('CREATED', 'SUBMITTED', 'WITHDRAWN', 'APPROVED', 'APPROVED_WITH_EDITS',
                                                             'CHANGES_REQUESTED', 'REJECTED', 'OUTDATED', 'ARCHIVED')),
    previous_status VARCHAR(20),
    new_status      VARCHAR(20)   NOT NULL,
    comment         VARCHAR(2000),
    details         JSONB         NOT NULL DEFAULT '{}',
    actor_id        UUID          REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_events_resource ON review_events (resource_id, created_at DESC);
CREATE INDEX idx_review_events_project ON review_events (project_id, created_at DESC);

-- Background analyses (the syllabus report), leased like every other job.
CREATE TABLE analysis_jobs (
    id                UUID         PRIMARY KEY,
    project_id        UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    kind              VARCHAR(20)  NOT NULL CHECK (kind IN ('SYLLABUS')),
    status            VARCHAR(12)  NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    input             JSONB        NOT NULL,
    result_version_id UUID         REFERENCES resource_versions (id) ON DELETE SET NULL,
    error             VARCHAR(500),
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_until      TIMESTAMPTZ,
    created_by        UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);
CREATE INDEX idx_analysis_jobs_queue ON analysis_jobs (next_attempt_at) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_analysis_jobs_project ON analysis_jobs (project_id, created_at DESC);
-- One analysis of a kind at a time per project.
CREATE UNIQUE INDEX uq_analysis_one_running ON analysis_jobs (project_id, kind) WHERE status IN ('QUEUED', 'RUNNING');

-- ---- rules on versions ------------------------------------------------------------------------

CREATE FUNCTION leenax_check_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    must_differ boolean;
    author uuid;
    project uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.resource_id <> OLD.resource_id OR NEW.version_no <> OLD.version_no OR NEW.origin <> OLD.origin
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION 'A version keeps its resource, number, origin and author' USING ERRCODE = 'LX010';
        END IF;
        IF OLD.status <> 'DRAFT' AND (NEW.content IS DISTINCT FROM OLD.content OR NEW.content_hash <> OLD.content_hash) THEN
            RAISE EXCEPTION 'Only a draft can be changed; edit to make a new version' USING ERRCODE = 'LX010';
        END IF;
    END IF;

    IF NEW.status IN ('APPROVED', 'REJECTED', 'CHANGES_REQUESTED')
       AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        IF coalesce(leenax_mode() NOT IN ('user', 'admin'), true) THEN
            RAISE EXCEPTION 'Only a person can approve, reject or ask for changes' USING ERRCODE = 'LX011';
        END IF;
        SELECT p.reviewer_not_author, p.id INTO must_differ, project
          FROM resources r JOIN projects p ON p.id = r.project_id WHERE r.id = NEW.resource_id;
        IF NOT leenax_can_review(project) THEN
            RAISE EXCEPTION 'Only the project''s owners and reviewers can decide' USING ERRCODE = 'LX011';
        END IF;
        IF TG_OP = 'INSERT' THEN
            -- approved with edits: the author is whoever wrote the version under review
            SELECT v.created_by INTO author FROM resource_versions v WHERE v.id = NEW.based_on_version_id;
            IF author IS NULL AND NEW.based_on_version_id IS NULL THEN
                RAISE EXCEPTION 'A version can only start approved as an edit of the one under review' USING ERRCODE = 'LX011';
            END IF;
        ELSE
            author := OLD.created_by;
        END IF;
        IF coalesce(must_differ, true) AND author = leenax_uid() THEN
            RAISE EXCEPTION 'The reviewer must not be the author in this project' USING ERRCODE = 'LX012';
        END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_resource_versions_rules BEFORE INSERT OR UPDATE ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_check_version();

-- ---- row-level security ------------------------------------------------------------------------

ALTER TABLE resources ENABLE ROW LEVEL SECURITY;
ALTER TABLE resources FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON resources FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON resources FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));
CREATE POLICY change ON resources FOR UPDATE
    USING (leenax_can_write(project_id, created_by) OR leenax_can_review(project_id))
    WITH CHECK (leenax_can_write(project_id, created_by) OR leenax_can_review(project_id));
CREATE POLICY remove ON resources FOR DELETE USING (leenax_can_write(project_id, created_by));

-- Reviewers write versions too: their decisions change the status, and "approve with
-- edits" adds a version. The trigger above keeps content frozen outside drafts.
ALTER TABLE resource_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON resource_versions FOR SELECT
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY write ON resource_versions FOR ALL
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id
                   AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))))
    WITH CHECK (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id
                   AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));

ALTER TABLE resource_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_links FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON resource_links FOR SELECT
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY write ON resource_links FOR ALL
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND leenax_can_write(r.project_id, r.created_by)))
    WITH CHECK (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND leenax_can_write(r.project_id, r.created_by)));

-- The decision record: readable with the project, added by editors, reviewers and the
-- worker, and never changed or deleted (no update or delete policy).
ALTER TABLE review_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_events FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON review_events FOR SELECT
    USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY add ON review_events FOR INSERT
    WITH CHECK (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND r.project_id = review_events.project_id
                        AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));

ALTER TABLE analysis_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON analysis_jobs FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON analysis_jobs FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));
CREATE POLICY change ON analysis_jobs FOR UPDATE USING (leenax_can_write(project_id, created_by))
    WITH CHECK (leenax_can_write(project_id, created_by));

GRANT SELECT, INSERT, UPDATE, DELETE ON resources, resource_versions, resource_links, review_events, analysis_jobs TO leenax_app;
GRANT EXECUTE ON FUNCTION leenax_can_review(uuid) TO leenax_app;
