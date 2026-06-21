-- RFC 65: Email verification marker.
-- A nullable, mutable domain column (NOT covered by prevent_immutable_updates),
-- so marking a user verified is an ordinary versioned update captured in
-- users_versions. New users default to NULL (unverified). Written only by the
-- dedicated verification path, never the generic UserEdit/update path.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ NULL;

ALTER TABLE users_versions ADD COLUMN email_verified_at TIMESTAMPTZ NULL;

-- Replace the version-logging trigger function to copy NEW.email_verified_at into
-- the history row alongside the existing columns.
CREATE OR REPLACE FUNCTION log_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO users_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        email, name, display_name, password_hash, sso_provider_id, is_admin, email_verified_at
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.email, NEW.name, NEW.display_name, NEW.password_hash, NEW.sso_provider_id, NEW.is_admin, NEW.email_verified_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
