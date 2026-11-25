-- Increment E6: YouTube SEO research and reports.
--
-- Only YouTube's own Data API is used for search and public video numbers (no autocomplete,
-- no Keyword Planner). search.list has a small daily allowance shared by the whole install,
-- so every call is recorded and a daily budget is enforced before a research run starts.
-- What a run found is kept as it was found (seo_research); the SEO report, an approvable
-- resource per topic, refers to it and holds only what people edit (titles, description,
-- tags, plans). Every number shown carries a source or an "estimated" label.

-- ---- SEO reports are approvable, one per topic -----------------------------------------------------

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN ('SYLLABUS', 'TOPICS', 'NOTES', 'MCQ', 'QA', 'SEO'));
ALTER TABLE resources DROP CONSTRAINT ck_resources_shape;
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind = 'NOTES' AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind IN ('MCQ', 'QA', 'SEO') AND topic_id IS NOT NULL AND level IS NULL));

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check CHECK (kind IN ('SYLLABUS', 'NOTES', 'MCQ', 'QA', 'SEO'));

-- ---- the YouTube quota ledger --------------------------------------------------------------------

-- One row per YouTube Data API call. The daily allowance is per Google Cloud project, so it
-- is shared by every project here; quota_day is the Pacific-time day YouTube counts by.
CREATE TABLE seo_api_calls (
    id         UUID         PRIMARY KEY,
    project_id UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    quota_day  DATE         NOT NULL,
    method     VARCHAR(30)  NOT NULL CHECK (method IN ('search.list', 'videos.list')),
    units      INTEGER      NOT NULL CHECK (units > 0),
    query      VARCHAR(300),
    ok         BOOLEAN      NOT NULL,
    created_by UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_seo_api_calls_day ON seo_api_calls (quota_day, method);
CREATE INDEX idx_seo_api_calls_project ON seo_api_calls (project_id, created_at DESC);

-- Searches and units used on a day, across all projects (members only see their own
-- projects' rows, but the allowance is shared, so the total is counted here).
CREATE FUNCTION leenax_seo_quota_used(p_day date) RETURNS TABLE (searches bigint, units bigint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
    SELECT count(*) FILTER (WHERE method = 'search.list'), coalesce(sum(units), 0)
      FROM seo_api_calls WHERE quota_day = p_day
$$;
REVOKE ALL ON FUNCTION leenax_seo_quota_used(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION leenax_seo_quota_used(date) TO leenax_app;

-- ---- cached search results -----------------------------------------------------------------------

-- Public search results and video numbers, kept for 7 days so a repeat costs nothing.
CREATE TABLE seo_search_cache (
    id           UUID         PRIMARY KEY,
    project_id   UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    query_key    VARCHAR(400) NOT NULL,
    query        VARCHAR(300) NOT NULL,
    language     VARCHAR(10),
    region       VARCHAR(2),
    results      JSONB        NOT NULL,
    retrieved_at TIMESTAMPTZ  NOT NULL,
    created_by   UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_seo_search_cache ON seo_search_cache (project_id, query_key, retrieved_at DESC);

-- ---- Google Trends downloads ---------------------------------------------------------------------

-- A CSV downloaded from the Trends website and imported by hand. Values are relative,
-- 0 to 100 within that one download; they are never shown as search volumes.
CREATE TABLE seo_trends (
    id          UUID         PRIMARY KEY,
    project_id  UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    filename    VARCHAR(200) NOT NULL,
    geo         VARCHAR(100),
    period      VARCHAR(20)  NOT NULL CHECK (period IN ('DAY', 'WEEK', 'MONTH', 'OTHER')),
    first_date  DATE,
    last_date   DATE,
    terms       JSONB        NOT NULL,
    series      JSONB        NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_by  UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_seo_trends_project ON seo_trends (project_id, status, created_at DESC);

-- ---- research results ----------------------------------------------------------------------------

-- Everything one research run found: keywords with their measured or estimated numbers,
-- competitor videos with their public numbers, AI ideas and analysis, and the sources.
-- Kept as found: no update or delete.
CREATE TABLE seo_research (
    id          UUID        PRIMARY KEY,
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    topic_id    VARCHAR(40) NOT NULL,
    job_id      UUID        REFERENCES analysis_jobs (id) ON DELETE SET NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'COMPLETE' CHECK (status IN ('COMPLETE', 'PARTIAL')),
    data        JSONB       NOT NULL,
    created_by  UUID        REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_seo_research_topic ON seo_research (project_id, topic_id, created_at DESC);
CREATE INDEX idx_seo_research_status ON seo_research (project_id, status);

-- ---- row-level security ------------------------------------------------------------------------

ALTER TABLE seo_api_calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE seo_api_calls FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON seo_api_calls FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON seo_api_calls FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));

ALTER TABLE seo_search_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE seo_search_cache FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON seo_search_cache FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON seo_search_cache FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));

ALTER TABLE seo_trends ENABLE ROW LEVEL SECURITY;
ALTER TABLE seo_trends FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON seo_trends FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON seo_trends FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));
-- Removing an import hides it from new research; research that used it keeps its copy.
CREATE POLICY change ON seo_trends FOR UPDATE USING (leenax_can_write(project_id, created_by))
    WITH CHECK (leenax_can_write(project_id, created_by));

ALTER TABLE seo_research ENABLE ROW LEVEL SECURITY;
ALTER TABLE seo_research FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON seo_research FOR SELECT USING (leenax_can_read(project_id, created_by));
CREATE POLICY add ON seo_research FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by));

-- A Trends import's data never changes; only whether it's offered for new research.
CREATE FUNCTION leenax_check_seo_trends() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.terms <> OLD.terms OR NEW.series <> OLD.series OR NEW.filename <> OLD.filename OR NEW.project_id <> OLD.project_id THEN
        RAISE EXCEPTION 'An imported Trends file is kept as imported' USING ERRCODE = 'LX016';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END $$;
CREATE TRIGGER trg_seo_trends_rules BEFORE UPDATE ON seo_trends FOR EACH ROW EXECUTE FUNCTION leenax_check_seo_trends();

GRANT SELECT, INSERT ON seo_api_calls, seo_search_cache, seo_research TO leenax_app;
GRANT SELECT, INSERT, UPDATE ON seo_trends TO leenax_app;
