-- Remove the fixed seat ceiling while retaining the last-administrator invariant.
-- Existing users, invitations, passwords and project memberships are untouched.
DELETE FROM app_settings WHERE key = 'max_active_users';

CREATE INDEX IF NOT EXISTS idx_users_status_created ON users (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_display_name_prefix ON users (lower(display_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_users_email_prefix ON users (lower(email) text_pattern_ops);

CREATE OR REPLACE FUNCTION leenax_check_users() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
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
