-- Invitation-only remains the safe default. Public registration is enabled only
-- by an administrator when outbound email is configured for verification links.
INSERT INTO app_settings (key, value)
VALUES ('registration_mode', 'INVITATION_ONLY')
ON CONFLICT (key) DO NOTHING;
