-- Increment E5: academic QA and QA reports.
--
-- A QA run checks one version of notes or a question bank, claim by claim, against the
-- course documents, and records what it found as issues. Issues never change content:
-- a correction is a proposal that an editor accepts (making or changing a draft) or
-- rejects. Accepted corrections are rechecked. A critical issue that is still open
-- blocks approval of the notes or bank it belongs to, and of the topic's QA report.
--
-- Critical issues are exactly these kinds (specification, section 6): a factual error,
-- a wrong answer key, an ambiguous question, a missing core syllabus topic, and an
-- unresolved contradiction. Every other kind is major or minor.

-- ---- QA reports are approvable, one per topic ------------------------------------------------------

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN ('SYLLABUS', 'TOPICS', 'NOTES', 'MCQ', 'QA'));
ALTER TABLE resources DROP CONSTRAINT ck_resources_shape;
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind = 'NOTES' AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind IN ('MCQ', 'QA') AND topic_id IS NOT NULL AND level IS NULL));

-- ---- QA jobs -------------------------------------------------------------------------------------

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check CHECK (kind IN ('SYLLABUS', 'NOTES', 'MCQ', 'QA'));
-- Reviewers may start QA too: checking is part of reviewing.
CREATE POLICY add_qa ON analysis_jobs FOR INSERT WITH CHECK (kind = 'QA' AND leenax_can_review(project_id));
CREATE POLICY change_qa ON analysis_jobs FOR UPDATE USING (kind = 'QA' AND leenax_can_review(project_id))
    WITH CHECK (kind = 'QA' AND leenax_can_review(project_id));

-- ---- runs ----------------------------------------------------------------------------------------

-- One check of one version, as it was then. result holds the counts per category and every claim checked
-- with its basis. A run is a record: never changed or deleted.
CREATE TABLE qa_runs (
    id             UUID         PRIMARY KEY,
    project_id     UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id    UUID         NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_id     UUID         NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    -- the exact content checked: a draft can change after its check
    content_hash   CHAR(64)     NOT NULL,
    job_id         UUID         REFERENCES analysis_jobs (id) ON DELETE SET NULL,
    mode           VARCHAR(10)  NOT NULL CHECK (mode IN ('CHECK', 'RECHECK')),
    model          VARCHAR(120) NOT NULL,
    provider       VARCHAR(60),
    second_model   VARCHAR(120),
    writer_model   VARCHAR(120),
    independent    BOOLEAN      NOT NULL,
    result         JSONB        NOT NULL,
    created_by     UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_qa_runs_resource ON qa_runs (resource_id, created_at DESC);
CREATE INDEX idx_qa_runs_version ON qa_runs (version_id);

-- ---- issues --------------------------------------------------------------------------------------

CREATE TABLE qa_issues (
    id                  UUID          PRIMARY KEY,
    project_id          UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id         UUID          NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    run_id              UUID          NOT NULL REFERENCES qa_runs (id) ON DELETE CASCADE,
    found_in_version_id UUID          NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    number              INTEGER       NOT NULL,
    -- where: a question id, or the notes heading the passage sits under
    item_ref            VARCHAR(80),
    item_label          VARCHAR(300),
    category            VARCHAR(20)   NOT NULL CHECK (category IN ('ACCURACY', 'COVERAGE', 'EXPLANATIONS', 'REFERENCES',
                                                                   'MCQ_KEYS', 'DIFFICULTY', 'DUPLICATES')),
    type                VARCHAR(30)   NOT NULL CHECK (type IN (
        'FACTUAL_ERROR', 'WRONG_KEY', 'AMBIGUOUS', 'MISSING_TOPIC', 'CONTRADICTION',
        'UNSUPPORTED_CLAIM', 'MATH_ERROR', 'CODE_ERROR', 'INCONSISTENT', 'WEAK_EXPLANATION', 'WEAK_EXAMPLE',
        'BAD_REFERENCE', 'COVERAGE_GAP', 'WRONG_DIFFICULTY', 'DUPLICATE', 'RULE_BREAK', 'GRAMMAR', 'OTHER')),
    severity            VARCHAR(10)   NOT NULL CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR')),
    original            VARCHAR(4000),
    explanation         VARCHAR(4000) NOT NULL,
    -- UPLOADED (a course document page), EXTERNAL (a link the AI gave, not opened),
    -- AI (the checker's own explanation), ESTIMATE (a rule of thumb), UNVERIFIED
    basis               VARCHAR(10)   NOT NULL CHECK (basis IN ('UPLOADED', 'EXTERNAL', 'AI', 'ESTIMATE', 'UNVERIFIED')),
    evidence            JSONB         NOT NULL DEFAULT '[]',
    suggestion          VARCHAR(4000),
    -- a second model's view of the finding: [{model, agrees, note}]
    confirmations       JSONB         NOT NULL DEFAULT '[]',
    status              VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CORRECTED', 'RESOLVED', 'DISMISSED')),
    status_note         VARCHAR(2000),
    -- the latest recheck: {runId, versionId, versionNo, fixed, note, at}
    recheck             JSONB,
    decided_by          UUID          REFERENCES users (id) ON DELETE SET NULL,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_qa_critical_kinds CHECK (
        (severity = 'CRITICAL') = (type IN ('FACTUAL_ERROR', 'WRONG_KEY', 'AMBIGUOUS', 'MISSING_TOPIC', 'CONTRADICTION'))),
    CONSTRAINT ck_qa_dismiss_reason CHECK (status <> 'DISMISSED' OR length(btrim(coalesce(status_note, ''))) > 0),
    UNIQUE (resource_id, number)
);
CREATE INDEX idx_qa_issues_resource ON qa_issues (resource_id, status);
CREATE INDEX idx_qa_issues_open_critical ON qa_issues (resource_id) WHERE severity = 'CRITICAL' AND status IN ('OPEN', 'CORRECTED');

-- ---- corrections ---------------------------------------------------------------------------------

-- A proposed change for one issue. For notes: {"find": ..., "replace": ...}; for a
-- question: {"questionId": ..., "answer"?, "options"?, "stem"?, "explanation"?}.
CREATE TABLE qa_corrections (
    id                 UUID          PRIMARY KEY,
    project_id         UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    issue_id           UUID          NOT NULL REFERENCES qa_issues (id) ON DELETE CASCADE,
    proposal           JSONB         NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED')),
    applied_version_id UUID          REFERENCES resource_versions (id) ON DELETE SET NULL,
    note               VARCHAR(2000),
    proposed_by        UUID          REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    decided_by         UUID          REFERENCES users (id) ON DELETE SET NULL,
    decided_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_qa_corrections_issue ON qa_corrections (issue_id);

-- ---- rules ---------------------------------------------------------------------------------------

-- A finding stays as it was found. Only its status, the decision on it, its recheck and
-- a second model's confirmations change, and only along these paths:
--   OPEN → CORRECTED (an editor accepted a correction), OPEN/CORRECTED → RESOLVED (a recheck
--   found it fixed), CORRECTED → OPEN (a recheck found it isn't), OPEN/CORRECTED → DISMISSED
--   and DISMISSED/RESOLVED → OPEN (a person who reviews, with a reason). Dismissing a critical
--   issue follows the project's "reviewer must not be the author" setting.
CREATE FUNCTION leenax_check_qa_issue() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    must_differ boolean;
    author uuid;
BEGIN
    IF NEW.project_id <> OLD.project_id OR NEW.resource_id <> OLD.resource_id OR NEW.run_id <> OLD.run_id
       OR NEW.found_in_version_id <> OLD.found_in_version_id OR NEW.number <> OLD.number
       OR NEW.item_ref IS DISTINCT FROM OLD.item_ref OR NEW.item_label IS DISTINCT FROM OLD.item_label
       OR NEW.category <> OLD.category OR NEW.type <> OLD.type OR NEW.severity <> OLD.severity
       OR NEW.original IS DISTINCT FROM OLD.original OR NEW.explanation <> OLD.explanation OR NEW.basis <> OLD.basis
       OR NEW.evidence <> OLD.evidence OR NEW.suggestion IS DISTINCT FROM OLD.suggestion OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'A QA finding is kept as it was found' USING ERRCODE = 'LX015';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('CORRECTED', 'RESOLVED', 'DISMISSED'))
             OR (OLD.status = 'CORRECTED' AND NEW.status IN ('OPEN', 'RESOLVED', 'DISMISSED'))
             OR (OLD.status IN ('DISMISSED', 'RESOLVED') AND NEW.status = 'OPEN')) THEN
            RAISE EXCEPTION 'An issue can''t go from % to %', OLD.status, NEW.status USING ERRCODE = 'LX015';
        END IF;
        IF NEW.status = 'RESOLVED' AND NEW.recheck IS NOT DISTINCT FROM OLD.recheck THEN
            RAISE EXCEPTION 'Only a recheck resolves an issue' USING ERRCODE = 'LX015';
        END IF;
        IF NEW.status = 'DISMISSED' OR (OLD.status IN ('DISMISSED', 'RESOLVED') AND NEW.recheck IS NOT DISTINCT FROM OLD.recheck) THEN
            IF coalesce(leenax_mode() NOT IN ('user', 'admin'), true) OR NOT leenax_can_review(NEW.project_id) THEN
                RAISE EXCEPTION 'Only the project''s owners and reviewers dismiss or reopen issues' USING ERRCODE = 'LX011';
            END IF;
            IF NEW.status = 'DISMISSED' AND NEW.severity = 'CRITICAL' THEN
                SELECT p.reviewer_not_author INTO must_differ FROM projects p WHERE p.id = NEW.project_id;
                SELECT v.created_by INTO author FROM resource_versions v WHERE v.id = NEW.found_in_version_id;
                IF coalesce(must_differ, true) AND author = leenax_uid() THEN
                    RAISE EXCEPTION 'The reviewer must not be the author in this project' USING ERRCODE = 'LX012';
                END IF;
            END IF;
            NEW.decided_by := leenax_uid();
            NEW.decided_at := now();
        END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END $$;
CREATE TRIGGER trg_qa_issues_rules BEFORE UPDATE ON qa_issues FOR EACH ROW EXECUTE FUNCTION leenax_check_qa_issue();

CREATE FUNCTION leenax_check_qa_correction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.proposal <> OLD.proposal OR NEW.issue_id <> OLD.issue_id OR NEW.created_at <> OLD.created_at
       OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by THEN
        RAISE EXCEPTION 'A proposed correction is kept as it was proposed' USING ERRCODE = 'LX015';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF OLD.status <> 'PROPOSED' THEN
            RAISE EXCEPTION 'This correction was already decided' USING ERRCODE = 'LX015';
        END IF;
        IF coalesce(leenax_mode() NOT IN ('user', 'admin'), true) THEN
            RAISE EXCEPTION 'Only a person accepts or rejects a correction' USING ERRCODE = 'LX011';
        END IF;
        IF NEW.status = 'ACCEPTED' AND NOT leenax_can_write(NEW.project_id, NULL) THEN
            RAISE EXCEPTION 'Only the project''s owners and editors accept corrections' USING ERRCODE = 'LX011';
        END IF;
        NEW.decided_by := leenax_uid();
        NEW.decided_at := now();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_qa_corrections_rules BEFORE UPDATE ON qa_corrections FOR EACH ROW EXECUTE FUNCTION leenax_check_qa_correction();

-- The blocking rule, in the database too: notes, a bank or a QA report can't become
-- Approved while a critical issue is open on it or, for a report, on what it covers.
CREATE FUNCTION leenax_check_qa_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    open_critical integer;
BEGIN
    IF NEW.status = 'APPROVED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT count(*) INTO open_critical FROM qa_issues i
         WHERE i.severity = 'CRITICAL' AND i.status IN ('OPEN', 'CORRECTED')
           AND (i.resource_id = NEW.resource_id
                OR i.resource_id IN (SELECT l.depends_on_id FROM resource_links l JOIN resources r ON r.id = l.resource_id
                                      WHERE l.resource_id = NEW.resource_id AND r.kind = 'QA'));
        IF open_critical > 0 THEN
            RAISE EXCEPTION 'QA shows % open critical issue(s)', open_critical USING ERRCODE = 'LX014';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_resource_versions_qa_gate BEFORE INSERT OR UPDATE ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_check_qa_gate();

-- ---- row-level security ------------------------------------------------------------------------

ALTER TABLE qa_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE qa_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON qa_runs FOR SELECT USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY add ON qa_runs FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND r.project_id = qa_runs.project_id
            AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));

ALTER TABLE qa_issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE qa_issues FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON qa_issues FOR SELECT USING (EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id));
CREATE POLICY add ON qa_issues FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM resources r WHERE r.id = resource_id AND r.project_id = qa_issues.project_id
            AND (leenax_can_write(r.project_id, r.created_by) OR leenax_can_review(r.project_id))));
CREATE POLICY change ON qa_issues FOR UPDATE
    USING (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id))
    WITH CHECK (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id));

ALTER TABLE qa_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE qa_corrections FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON qa_corrections FOR SELECT USING (EXISTS (SELECT 1 FROM qa_issues i WHERE i.id = issue_id));
CREATE POLICY add ON qa_corrections FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM qa_issues i WHERE i.id = issue_id AND i.project_id = qa_corrections.project_id)
    AND (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id)));
CREATE POLICY change ON qa_corrections FOR UPDATE
    USING (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id))
    WITH CHECK (leenax_can_write(project_id, NULL) OR leenax_can_review(project_id));

GRANT SELECT, INSERT, UPDATE ON qa_runs, qa_issues, qa_corrections TO leenax_app;
