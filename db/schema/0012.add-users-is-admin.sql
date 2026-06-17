-- RFC 60: Admin authorization flag.
-- A normal, mutable domain column (NOT covered by prevent_immutable_updates),
-- so privilege grants are ordinary versioned updates captured in users_versions.

ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE users_versions ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT false;

-- Replace the version-logging trigger function to copy NEW.is_admin into the
-- history row alongside the existing columns.
CREATE OR REPLACE FUNCTION log_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO users_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        email, name, display_name, password_hash, sso_provider_id, is_admin
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.email, NEW.name, NEW.display_name, NEW.password_hash, NEW.sso_provider_id, NEW.is_admin
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
