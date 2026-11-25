-- Increment E1: up to five users, roles, project members, and access enforced by
-- PostgreSQL row-level security.
--
-- How access works
--   Every connection the app takes from its pool first says who is acting:
--     leenax.mode    = system | worker | admin | user | anon
--     leenax.user_id = the acting user (workers: the owner of the job they run)
--   system, worker and admin see everything. A user sees a row when it belongs to a
--   project they are a member of, or when it has no project and they created it.
--   Writing needs the Owner or Editor role in the project. An unset mode sees nothing,
--   so a connection that skipped the step fails closed instead of leaking.

-- ---- who is acting -------------------------------------------------------------------------

CREATE FUNCTION leenax_mode() RETURNS text LANGUAGE sql STABLE AS
$$ SELECT current_setting('leenax.mode', true) $$;

CREATE FUNCTION leenax_uid() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('leenax.user_id', true), '')::uuid $$;

CREATE FUNCTION leenax_is_super() RETURNS boolean LANGUAGE sql STABLE AS
$$ SELECT coalesce(leenax_mode() IN ('system', 'worker', 'admin'), false) $$;

-- ---- users ------------------------------------------------------------------------------------

DROP INDEX uq_users_single_owner;

ALTER TABLE users
    ADD COLUMN role                VARCHAR(12) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    ADD COLUMN status              VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED', 'ACTIVE', 'DEACTIVATED')),
    ADD COLUMN can_create_projects BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN invited_by          UUID        REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN activated_at        TIMESTAMPTZ,
    ADD COLUMN deactivated_at      TIMESTAMPTZ,
    ALTER COLUMN password_hash DROP NOT NULL;

-- The owner from phases 1-8 becomes the first administrator.
UPDATE users SET role = 'ADMIN', activated_at = created_at;

ALTER TABLE users ADD CONSTRAINT ck_users_active_has_password
    CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL);

CREATE TABLE app_settings (
    key        VARCHAR(60)  PRIMARY KEY,
    value      VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Seats: active plus invited accounts. Raise it here (or in Settings, as an
-- administrator) if the team grows; deactivated accounts never count.
INSERT INTO app_settings (key, value) VALUES ('max_active_users', '5');

-- Enforced here as well as in the app, so no code path can create a sixth seat,
-- and the last active administrator can't be removed.
CREATE FUNCTION leenax_check_users() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    seat_limit int;
    taken      int;
BEGIN
    IF NEW.status IN ('ACTIVE', 'INVITED')
       AND (TG_OP = 'INSERT' OR OLD.status NOT IN ('ACTIVE', 'INVITED')) THEN
        PERFORM pg_advisory_xact_lock(hashtext('leenax_user_seats'));
        SELECT coalesce((SELECT value::int FROM app_settings WHERE key = 'max_active_users'), 5) INTO seat_limit;
        SELECT count(*) INTO taken FROM users WHERE status IN ('ACTIVE', 'INVITED') AND id <> NEW.id;
        IF taken >= seat_limit THEN
            RAISE EXCEPTION 'Leenax Studio allows % active or invited users', seat_limit
                USING ERRCODE = 'LX001';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.role = 'ADMIN' AND OLD.status = 'ACTIVE'
       AND (NEW.role <> 'ADMIN' OR NEW.status <> 'ACTIVE') THEN
        PERFORM pg_advisory_xact_lock(hashtext('leenax_user_seats'));
        IF NOT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> NEW.id) THEN
            RAISE EXCEPTION 'Leenax Studio needs at least one active administrator'
                USING ERRCODE = 'LX002';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_users_check BEFORE INSERT OR UPDATE OF status, role ON users
    FOR EACH ROW EXECUTE FUNCTION leenax_check_users();

CREATE FUNCTION leenax_no_user_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Users are deactivated, not deleted, so their history stays' USING ERRCODE = 'LX003';
END $$;

CREATE TRIGGER trg_users_no_delete BEFORE DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION leenax_no_user_delete();

-- One-time links for account activation and password reset. Only a SHA-256 of the
-- token is stored; the link is shown once to the administrator.
CREATE TABLE user_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(12) NOT NULL CHECK (purpose IN ('ACTIVATE', 'RESET')),
    token_hash  BYTEA       NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_by  UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_tokens_user ON user_tokens (user_id, purpose);

CREATE TABLE login_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        REFERENCES users (id) ON DELETE SET NULL,
    email       VARCHAR(254) NOT NULL,
    success     BOOLEAN     NOT NULL,
    reason      VARCHAR(40),
    ip_address  VARCHAR(64),
    user_agent  VARCHAR(300),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_events_created ON login_events (created_at DESC);
CREATE INDEX idx_login_events_user ON login_events (user_id, created_at DESC);

-- ---- projects and members --------------------------------------------------------------------

ALTER TABLE projects
    ADD COLUMN kind                VARCHAR(12)  NOT NULL DEFAULT 'CREATOR' CHECK (kind IN ('CREATOR', 'EDUCATION')),
    ADD COLUMN subject_name        VARCHAR(200),
    ADD COLUMN module_code         VARCHAR(40),
    ADD COLUMN academic_year       VARCHAR(20),
    ADD COLUMN semester            VARCHAR(20),
    ADD COLUMN university          VARCHAR(200),
    ADD COLUMN department          VARCHAR(200),
    ADD COLUMN reviewer_not_author BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN created_by          UUID         REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid();

CREATE INDEX idx_projects_module_code ON projects (lower(module_code)) WHERE module_code IS NOT NULL;

CREATE TABLE project_members (
    project_id  UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(12) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'REVIEWER', 'VIEWER')),
    added_by    UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX idx_project_members_user ON project_members (user_id);

-- Whoever creates a project owns it.
CREATE FUNCTION leenax_project_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.created_by IS NOT NULL THEN
        INSERT INTO project_members (project_id, user_id, role, added_by)
        VALUES (NEW.id, NEW.created_by, 'OWNER', NEW.created_by)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_projects_owner AFTER INSERT ON projects
    FOR EACH ROW EXECUTE FUNCTION leenax_project_owner();

-- ---- access rules -----------------------------------------------------------------------------

CREATE FUNCTION leenax_project_role(p_project uuid) RETURNS text LANGUAGE sql STABLE AS
$$ SELECT m.role FROM project_members m WHERE m.project_id = p_project AND m.user_id = leenax_uid() $$;

CREATE FUNCTION leenax_can_read(p_project uuid, p_owner uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT leenax_is_super() OR (coalesce(leenax_mode() = 'user', false) AND (
        CASE WHEN p_project IS NULL THEN p_owner = leenax_uid()
             ELSE leenax_project_role(p_project) IS NOT NULL END))
$$;

CREATE FUNCTION leenax_can_write(p_project uuid, p_owner uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT leenax_is_super() OR (coalesce(leenax_mode() = 'user', false) AND (
        CASE WHEN p_project IS NULL THEN p_owner = leenax_uid()
             ELSE leenax_project_role(p_project) IN ('OWNER', 'EDITOR') END))
$$;

-- ---- ownership on every piece of content ---------------------------------------------------------

DO $$
DECLARE
    t text;
    owner uuid := (SELECT id FROM users WHERE role = 'ADMIN' ORDER BY created_at LIMIT 1);
BEGIN
    FOREACH t IN ARRAY ARRAY['media_assets', 'generation_jobs', 'auto_edit_jobs', 'render_jobs', 'timelines',
                             'scripts', 'storyboard_projects', 'storyboard_characters', 'subtitle_tracks',
                             'thumbnail_designs', 'posts', 'voice_profiles']
    LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN created_by UUID REFERENCES users (id) ON DELETE SET NULL', t);
        EXECUTE format('UPDATE %I SET created_by = $1', t) USING owner;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN created_by SET DEFAULT leenax_uid()', t);
        EXECUTE format('CREATE INDEX %I ON %I (created_by)', 'idx_' || t || '_created_by', t);
    END LOOP;
    UPDATE projects SET created_by = owner;
    INSERT INTO project_members (project_id, user_id, role, added_by)
        SELECT id, owner, 'OWNER', owner FROM projects WHERE owner IS NOT NULL;
END $$;

-- A saved voice can be shared with the whole team (a narrator), or kept to its creator.
ALTER TABLE voice_profiles ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;

-- Who spent what, and who did what.
ALTER TABLE usage_logs ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid();
ALTER TABLE audit_logs ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE SET NULL DEFAULT leenax_uid();
CREATE INDEX idx_usage_logs_user ON usage_logs (user_id, created_at DESC);
CREATE INDEX idx_audit_user ON audit_logs (user_id, created_at DESC);

-- ---- row-level security ------------------------------------------------------------------------

-- Content with its own project and owner.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['media_assets', 'generation_jobs', 'auto_edit_jobs', 'render_jobs', 'timelines',
                             'scripts', 'storyboard_projects', 'storyboard_characters', 'subtitle_tracks',
                             'thumbnail_designs']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY read ON %I FOR SELECT USING (leenax_can_read(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY add ON %I FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY change ON %I FOR UPDATE USING (leenax_can_write(project_id, created_by)) '
                       'WITH CHECK (leenax_can_write(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY remove ON %I FOR DELETE USING (leenax_can_write(project_id, created_by))', t);
    END LOOP;
END $$;

-- Projects: members see them; owners change them; creating one needs the permission.
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON projects FOR SELECT USING (leenax_is_super() OR leenax_project_role(id) IS NOT NULL);
CREATE POLICY add ON projects FOR INSERT WITH CHECK (leenax_is_super() OR (
    coalesce(leenax_mode() = 'user', false)
    AND created_by = leenax_uid()
    AND EXISTS (SELECT 1 FROM users u WHERE u.id = leenax_uid() AND u.can_create_projects)));
CREATE POLICY change ON projects FOR UPDATE
    USING (leenax_is_super() OR leenax_project_role(id) IN ('OWNER', 'EDITOR'))
    WITH CHECK (leenax_is_super() OR leenax_project_role(id) IN ('OWNER', 'EDITOR'));
CREATE POLICY remove ON projects FOR DELETE USING (leenax_is_super() OR leenax_project_role(id) = 'OWNER');

-- Posts have no project: they belong to whoever made them.
ALTER TABLE posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE posts FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON posts FOR SELECT USING (leenax_can_read(NULL, created_by));
CREATE POLICY add ON posts FOR INSERT WITH CHECK (leenax_can_write(NULL, created_by));
CREATE POLICY change ON posts FOR UPDATE USING (leenax_can_write(NULL, created_by)) WITH CHECK (leenax_can_write(NULL, created_by));
CREATE POLICY remove ON posts FOR DELETE USING (leenax_can_write(NULL, created_by));

-- Voices: the creator's, or shared with everyone to use (only the creator changes them).
ALTER TABLE voice_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE voice_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON voice_profiles FOR SELECT
    USING (leenax_can_read(NULL, created_by) OR (shared AND coalesce(leenax_mode() = 'user', false)));
CREATE POLICY add ON voice_profiles FOR INSERT WITH CHECK (leenax_can_write(NULL, created_by));
CREATE POLICY change ON voice_profiles FOR UPDATE USING (leenax_can_write(NULL, created_by)) WITH CHECK (leenax_can_write(NULL, created_by));
CREATE POLICY remove ON voice_profiles FOR DELETE USING (leenax_can_write(NULL, created_by));

-- Rows that live inside another row follow their parent. The sub-select is itself
-- filtered by the parent's policy, so "the parent is visible" is exactly the rule.
ALTER TABLE storyboard_scenes ENABLE ROW LEVEL SECURITY;
ALTER TABLE storyboard_scenes FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON storyboard_scenes FOR SELECT
    USING (EXISTS (SELECT 1 FROM storyboard_projects b WHERE b.id = storyboard_id));
CREATE POLICY write ON storyboard_scenes FOR ALL
    USING (EXISTS (SELECT 1 FROM storyboard_projects b WHERE b.id = storyboard_id AND leenax_can_write(b.project_id, b.created_by)))
    WITH CHECK (EXISTS (SELECT 1 FROM storyboard_projects b WHERE b.id = storyboard_id AND leenax_can_write(b.project_id, b.created_by)));

ALTER TABLE storyboard_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE storyboard_references FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON storyboard_references FOR SELECT USING (
    EXISTS (SELECT 1 FROM storyboard_characters c WHERE c.id = character_id)
    OR EXISTS (SELECT 1 FROM storyboard_scenes s WHERE s.id = scene_id));
CREATE POLICY write ON storyboard_references FOR ALL
    USING (EXISTS (SELECT 1 FROM storyboard_characters c WHERE c.id = character_id AND leenax_can_write(c.project_id, c.created_by))
        OR EXISTS (SELECT 1 FROM storyboard_scenes s JOIN storyboard_projects b ON b.id = s.storyboard_id
                   WHERE s.id = scene_id AND leenax_can_write(b.project_id, b.created_by)))
    WITH CHECK (EXISTS (SELECT 1 FROM storyboard_characters c WHERE c.id = character_id AND leenax_can_write(c.project_id, c.created_by))
        OR EXISTS (SELECT 1 FROM storyboard_scenes s JOIN storyboard_projects b ON b.id = s.storyboard_id
                   WHERE s.id = scene_id AND leenax_can_write(b.project_id, b.created_by)));

ALTER TABLE post_targets ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_targets FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON post_targets FOR SELECT USING (EXISTS (SELECT 1 FROM posts p WHERE p.id = post_id));
CREATE POLICY write ON post_targets FOR ALL
    USING (EXISTS (SELECT 1 FROM posts p WHERE p.id = post_id AND leenax_can_write(NULL, p.created_by)))
    WITH CHECK (EXISTS (SELECT 1 FROM posts p WHERE p.id = post_id AND leenax_can_write(NULL, p.created_by)));

ALTER TABLE post_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE post_metrics FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON post_metrics FOR SELECT USING (EXISTS (SELECT 1 FROM post_targets t WHERE t.id = target_id));
CREATE POLICY write ON post_metrics FOR ALL USING (leenax_is_super()) WITH CHECK (leenax_is_super());

ALTER TABLE media_analyses ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_analyses FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON media_analyses FOR SELECT USING (EXISTS (SELECT 1 FROM media_assets m WHERE m.id = media_id));
CREATE POLICY write ON media_analyses FOR ALL
    USING (EXISTS (SELECT 1 FROM media_assets m WHERE m.id = media_id))
    WITH CHECK (EXISTS (SELECT 1 FROM media_assets m WHERE m.id = media_id));

-- ---- the restricted role ------------------------------------------------------------------------
-- The app's login (POSTGRES_USER in Docker) is a superuser, and superusers skip
-- row-level security. So every connection made for a person or a job switches to
-- this role first (see ActorDataSource): it can read and write data, but can't skip
-- the policies or change the schema.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'leenax_app') THEN
        CREATE ROLE leenax_app NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
    EXECUTE format('GRANT leenax_app TO %I', current_user);
END $$;

GRANT USAGE ON SCHEMA public TO leenax_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO leenax_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO leenax_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO leenax_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO leenax_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO leenax_app;
-- Settings and the seat limit are changed by administrators through the app.
