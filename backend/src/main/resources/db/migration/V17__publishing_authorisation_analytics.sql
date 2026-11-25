-- Increment E9: YouTube publishing and analytics.
--
-- "No publishing without an explicit authorisation record, and no public post without confirmation."
-- Every upload, to any platform, needs a person's authorisation of exactly what goes up: the file
-- and the post's settings (title, description, tags, privacy, playlists, captions), held as a hash.
-- Changing either after authorising means authorising again. Workers and AI jobs can't authorise.
-- For a lesson or quiz video, only the project's owner (or an administrator) authorises, the video
-- must be approved, the file must be its checked render, and the rights must be confirmed.
--
-- Analytics: snapshots of what the YouTube Analytics API returned, and the daily reach files
-- (thumbnail impressions and click-through rate) from the YouTube Reporting API.

-- ---- authorisation ------------------------------------------------------------------------------

-- Whether a target's settings would make the post public. YouTube: privacy "public". TikTok: only
-- "SELF_ONLY" is private. Facebook pages and Instagram posts are always public.
CREATE FUNCTION leenax_target_is_public(p_platform text, p_settings jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_platform
        -- a private video with publishAt becomes public at that time
        WHEN 'YOUTUBE' THEN coalesce(p_settings ->> 'privacy', '') = 'public' OR coalesce(p_settings ->> 'publishAt', '') <> ''
        WHEN 'TIKTOK'  THEN coalesce(p_settings ->> 'privacy', '') <> 'SELF_ONLY'
        ELSE true END
$$;

CREATE FUNCTION leenax_settings_hash(p_settings jsonb) RETURNS char(64) LANGUAGE sql IMMUTABLE AS $$
    SELECT encode(sha256(convert_to(coalesce(p_settings, '{}'::jsonb)::text, 'UTF8')), 'hex')
$$;

CREATE TABLE publish_authorisations (
    id                UUID         PRIMARY KEY,
    post_target_id    UUID         NOT NULL UNIQUE REFERENCES post_targets (id) ON DELETE CASCADE,
    media_id          UUID         NOT NULL,
    settings_hash     CHAR(64)     NOT NULL,
    platform          VARCHAR(12)  NOT NULL,
    public            BOOLEAN      NOT NULL,
    public_confirmed  BOOLEAN      NOT NULL DEFAULT false,
    rights_confirmed  BOOLEAN      NOT NULL DEFAULT false,
    project_id        UUID         REFERENCES projects (id) ON DELETE SET NULL,
    resource_id       UUID         REFERENCES resources (id) ON DELETE SET NULL,
    version_id        UUID         REFERENCES resource_versions (id) ON DELETE SET NULL,
    statement         TEXT         NOT NULL CHECK (length(statement) BETWEEN 10 AND 2000),
    authorised_by     UUID         NOT NULL REFERENCES users (id) DEFAULT leenax_uid(),
    authorised_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_publish_authorisations_project ON publish_authorisations (project_id, authorised_at DESC);

-- Filled in and checked by the database: the hash, the file, whether it's public; who may authorise.
CREATE FUNCTION leenax_check_authorisation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    t        record;
    v        record;
    n        integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        -- kept as given; only a link to a deleted project or video is cleared (ON DELETE SET NULL)
        IF (to_jsonb(NEW) - 'project_id' - 'resource_id' - 'version_id') = (to_jsonb(OLD) - 'project_id' - 'resource_id' - 'version_id')
           AND (NEW.project_id IS NULL OR NEW.project_id = OLD.project_id)
           AND (NEW.resource_id IS NULL OR NEW.resource_id = OLD.resource_id)
           AND (NEW.version_id IS NULL OR NEW.version_id = OLD.version_id) THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'An authorisation is kept as given' USING ERRCODE = 'LX023';
    END IF;
    IF coalesce(leenax_mode(), '') NOT IN ('user', 'admin') OR leenax_uid() IS NULL THEN
        RAISE EXCEPTION 'Only a signed-in person can authorise publishing' USING ERRCODE = 'LX020';
    END IF;
    NEW.authorised_by := leenax_uid();
    SELECT pt.settings, pt.platform, p.media_id, p.created_by INTO t
      FROM post_targets pt JOIN posts p ON p.id = pt.post_id WHERE pt.id = NEW.post_target_id;
    IF NOT FOUND OR t.media_id IS NULL THEN
        RAISE EXCEPTION 'Nothing to authorise' USING ERRCODE = 'LX020';
    END IF;
    IF t.created_by IS DISTINCT FROM leenax_uid() THEN
        RAISE EXCEPTION 'Only the person posting can authorise their own post' USING ERRCODE = 'LX020';
    END IF;
    NEW.media_id := t.media_id;
    NEW.platform := t.platform;
    NEW.settings_hash := leenax_settings_hash(t.settings);
    NEW.public := leenax_target_is_public(t.platform, t.settings);
    IF NEW.public AND NOT NEW.public_confirmed THEN
        RAISE EXCEPTION 'A public post needs its confirmation' USING ERRCODE = 'LX021';
    END IF;
    IF NEW.project_id IS NOT NULL OR NEW.version_id IS NOT NULL THEN
        -- an education video: the project owner or an administrator, the approved video, its checked render, rights confirmed
        IF NEW.project_id IS NULL OR NEW.version_id IS NULL THEN
            RAISE EXCEPTION 'An education publication names its project and video version' USING ERRCODE = 'LX022';
        END IF;
        IF leenax_mode() <> 'admin' AND coalesce(leenax_project_role(NEW.project_id), '') <> 'OWNER' THEN
            RAISE EXCEPTION 'Only the project owner or an administrator authorises YouTube publishing' USING ERRCODE = 'LX022';
        END IF;
        IF NOT NEW.rights_confirmed THEN
            RAISE EXCEPTION 'Confirm the rights to publish this video' USING ERRCODE = 'LX022';
        END IF;
        SELECT x.status, x.content_hash, r.kind, r.project_id, r.id AS rid INTO v
          FROM resource_versions x JOIN resources r ON r.id = x.resource_id WHERE x.id = NEW.version_id;
        IF NOT FOUND OR v.kind NOT IN ('LESSON_VIDEO', 'QUIZ_VIDEO') OR v.project_id <> NEW.project_id THEN
            RAISE EXCEPTION 'That is not a video of this project' USING ERRCODE = 'LX022';
        END IF;
        IF v.status <> 'APPROVED' THEN
            RAISE EXCEPTION 'Only an approved video can be published' USING ERRCODE = 'LX022';
        END IF;
        NEW.resource_id := v.rid;
        SELECT count(*) INTO n FROM video_renders vr JOIN render_jobs j ON j.id = vr.render_job_id
         WHERE vr.resource_id = v.rid AND vr.content_hash = v.content_hash AND j.status = 'COMPLETED'
           AND j.validation ->> 'ok' = 'true' AND j.output_media_id = t.media_id;
        IF n = 0 THEN
            RAISE EXCEPTION 'The file is not the checked render of the approved video' USING ERRCODE = 'LX022';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_publish_authorisations_check BEFORE INSERT OR UPDATE ON publish_authorisations
    FOR EACH ROW EXECUTE FUNCTION leenax_check_authorisation();

ALTER TABLE publish_authorisations ENABLE ROW LEVEL SECURITY;
ALTER TABLE publish_authorisations FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON publish_authorisations FOR SELECT USING (
    EXISTS (SELECT 1 FROM post_targets pt WHERE pt.id = post_target_id)
    OR (project_id IS NOT NULL AND leenax_can_read(project_id, NULL)));
CREATE POLICY add ON publish_authorisations FOR INSERT WITH CHECK (authorised_by = leenax_uid());
GRANT SELECT, INSERT ON publish_authorisations TO leenax_app;

-- Nothing starts uploading without an authorisation of exactly this file and these settings.
CREATE FUNCTION leenax_check_upload() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    n integer;
BEGIN
    IF NEW.status IN ('UPLOADING', 'PROCESSING', 'AWAITING_USER', 'PUBLISHED')
       AND (TG_OP = 'INSERT' OR OLD.status NOT IN ('UPLOADING', 'PROCESSING', 'AWAITING_USER', 'PUBLISHED')) THEN
        SELECT count(*) INTO n FROM publish_authorisations a JOIN posts p ON p.id = NEW.post_id
         WHERE a.post_target_id = NEW.id AND a.settings_hash = leenax_settings_hash(NEW.settings) AND a.media_id = p.media_id;
        IF n = 0 THEN
            RAISE EXCEPTION 'No authorisation record for this upload' USING ERRCODE = 'LX020';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_post_targets_upload_check BEFORE INSERT OR UPDATE ON post_targets
    FOR EACH ROW EXECUTE FUNCTION leenax_check_upload();

-- ---- education publications --------------------------------------------------------------------

-- Which approved video was published where (and which post carries it).
CREATE TABLE youtube_publications (
    id                 UUID         PRIMARY KEY,
    project_id         UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    resource_id        UUID         NOT NULL REFERENCES resources (id) ON DELETE CASCADE,
    version_id         UUID         NOT NULL REFERENCES resource_versions (id) ON DELETE CASCADE,
    topic_id           VARCHAR(40)  NOT NULL,
    post_id            UUID         REFERENCES posts (id) ON DELETE SET NULL,
    post_target_id     UUID         UNIQUE REFERENCES post_targets (id) ON DELETE SET NULL,
    authorisation_id   UUID         REFERENCES publish_authorisations (id) ON DELETE SET NULL,
    social_account_id  UUID         REFERENCES social_accounts (id) ON DELETE SET NULL,
    title              VARCHAR(100) NOT NULL,
    created_by         UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid(),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_youtube_publications_project ON youtube_publications (project_id, created_at DESC);
ALTER TABLE youtube_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE youtube_publications FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON youtube_publications FOR SELECT USING (leenax_can_read(project_id, NULL));
CREATE POLICY add ON youtube_publications FOR INSERT WITH CHECK (
    leenax_mode() = 'admin' OR coalesce(leenax_project_role(project_id), '') = 'OWNER');
GRANT SELECT, INSERT ON youtube_publications TO leenax_app;

-- Posts are personal, but every project reader may see how its publications are doing: status,
-- the video's address and any error. Nothing else of the post is shown.
CREATE FUNCTION leenax_youtube_publication_status(p_project uuid)
    RETURNS TABLE (publication_id uuid, status varchar, remote_id varchar, remote_url varchar, error_message varchar,
                   published_at timestamptz, run_at timestamptz, settings jsonb)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
    SELECT y.id, t.status, t.remote_id, t.remote_url, t.error_message, t.published_at, t.run_at,
           t.settings - 'captions' - 'description'
      FROM youtube_publications y JOIN post_targets t ON t.id = y.post_target_id
     WHERE y.project_id = p_project AND leenax_can_read(p_project, NULL)
$$;
REVOKE ALL ON FUNCTION leenax_youtube_publication_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION leenax_youtube_publication_status(uuid) TO leenax_app;

-- ---- analytics ----------------------------------------------------------------------------------

-- What a YouTube Analytics API query returned, kept as read (no figures are made up or filled in).
-- kind: BASIC (per video), RETENTION (one video), TRAFFIC (sources), SEARCH_TERMS (one video), CHANNEL.
CREATE TABLE youtube_analytics_snapshots (
    id                 UUID         PRIMARY KEY,
    -- the project whose dashboard took it (channel numbers only; no project content is stored)
    project_id         UUID         REFERENCES projects (id) ON DELETE SET NULL,
    social_account_id  UUID         NOT NULL REFERENCES social_accounts (id) ON DELETE CASCADE,
    kind               VARCHAR(14)  NOT NULL CHECK (kind IN ('BASIC', 'RETENTION', 'TRAFFIC', 'SEARCH_TERMS', 'CHANNEL')),
    video_id           VARCHAR(20),
    start_date         DATE         NOT NULL,
    end_date           DATE         NOT NULL CHECK (end_date >= start_date),
    data               JSONB        NOT NULL,
    fetched_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid()
);
CREATE INDEX idx_youtube_snapshots_project ON youtube_analytics_snapshots (project_id, fetched_at DESC);
CREATE INDEX idx_youtube_snapshots_lookup ON youtube_analytics_snapshots (social_account_id, kind, video_id, start_date, end_date, fetched_at DESC);

-- The Reporting API job that produces the daily reach files for a channel.
CREATE TABLE youtube_reporting_jobs (
    social_account_id  UUID         PRIMARY KEY REFERENCES social_accounts (id) ON DELETE CASCADE,
    report_type        VARCHAR(60)  NOT NULL,
    job_id             VARCHAR(120) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_checked_at    TIMESTAMPTZ,
    last_error         VARCHAR(500),
    created_by         UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid()
);

-- One row per day and video from the reach files. A later file for the same day (YouTube's
-- backfill) replaces the day's rows.
CREATE TABLE youtube_reach_daily (
    social_account_id  UUID         NOT NULL REFERENCES social_accounts (id) ON DELETE CASCADE,
    day                DATE         NOT NULL,
    video_id           VARCHAR(20)  NOT NULL,
    impressions        BIGINT       NOT NULL CHECK (impressions >= 0),
    ctr                DOUBLE PRECISION CHECK (ctr IS NULL OR (ctr >= 0 AND ctr <= 1)),
    report_id          VARCHAR(120) NOT NULL,
    imported_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (social_account_id, day, video_id)
);
CREATE TABLE youtube_reach_reports (
    social_account_id  UUID         NOT NULL REFERENCES social_accounts (id) ON DELETE CASCADE,
    report_id          VARCHAR(120) NOT NULL,
    start_time         TIMESTAMPTZ  NOT NULL,
    end_time           TIMESTAMPTZ  NOT NULL,
    create_time        TIMESTAMPTZ  NOT NULL,
    rows               INTEGER      NOT NULL,
    imported_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (social_account_id, report_id)
);

-- Like the connected accounts themselves (shared by the studio's users), channel analytics have
-- no per-project rule; they never hold project content.
GRANT SELECT, INSERT, UPDATE, DELETE ON youtube_analytics_snapshots, youtube_reporting_jobs, youtube_reach_daily, youtube_reach_reports TO leenax_app;
