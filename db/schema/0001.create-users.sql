CREATE TABLE users (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,
  
  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMPTZ NULL,

  -- Core Fields
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  display_name TEXT NULL,

  -- Authentication
  password_hash TEXT NULL,
  sso_provider_id TEXT NULL,

  -- Constraints
  CONSTRAINT users_email_length_check CHECK (length(email) <= 254),
  CONSTRAINT users_name_length_check CHECK (length(name) <= 255),
  CONSTRAINT users_display_name_length_check CHECK (display_name IS NULL OR length(display_name) <= 255),
  CONSTRAINT users_password_hash_length_check CHECK (password_hash IS NULL OR length(password_hash) <= 255),
  CONSTRAINT users_sso_provider_id_length_check CHECK (sso_provider_id IS NULL OR length(sso_provider_id) <= 255),
  CONSTRAINT users_email_lowercase_check CHECK (email = LOWER(email)),
  CONSTRAINT users_email_format_check CHECK (email LIKE '%@%'),
  CONSTRAINT users_name_not_empty_check CHECK (length(trim(name)) > 0),
  CONSTRAINT users_email_not_empty_check CHECK (length(trim(email)) > 0),
  CONSTRAINT users_display_name_not_empty_check CHECK (display_name IS NULL OR length(trim(display_name)) > 0),
  CONSTRAINT users_password_hash_not_empty_check CHECK (password_hash IS NULL OR length(trim(password_hash)) > 0),
  CONSTRAINT users_sso_provider_id_not_empty_check CHECK (sso_provider_id IS NULL OR length(trim(sso_provider_id)) > 0),
  CONSTRAINT users_name_trim_check CHECK (name = trim(name)),
  CONSTRAINT users_email_trim_check CHECK (email = trim(email)),
  CONSTRAINT users_display_name_trim_check CHECK (display_name IS NULL OR display_name = trim(display_name)),
  CONSTRAINT users_auth_method_check CHECK (password_hash IS NOT NULL OR sso_provider_id IS NOT NULL)
);

-- Ensure an email can only be uniquely registered once among active users
CREATE UNIQUE INDEX users_email_unique_active_idx ON users (email) WHERE deleted_at IS NULL;

-- Index for fast SSO logins
CREATE INDEX users_sso_provider_id_idx ON users (sso_provider_id) WHERE sso_provider_id IS NOT NULL;

-- Version History Table
CREATE TABLE users_versions (
  id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  display_name TEXT NULL,
  password_hash TEXT NULL,
  sso_provider_id TEXT NULL,
  PRIMARY KEY (id, version)
);

-- Index for efficient date-range timeline queries on a specific user's history
CREATE INDEX users_versions_id_updated_at_idx ON users_versions (id, updated_at);

CREATE OR REPLACE FUNCTION trim_users_strings()
RETURNS TRIGGER AS $$
BEGIN
    NEW.email = LOWER(trim(NEW.email));
    NEW.name = trim(NEW.name);
    IF NEW.display_name IS NOT NULL THEN
        NEW.display_name = trim(NEW.display_name);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO users_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        email, name, display_name, password_hash, sso_provider_id
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.email, NEW.name, NEW.display_name, NEW.password_hash, NEW.sso_provider_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- BEFORE triggers execute in alphabetical order by name if not specified.
CREATE TRIGGER trigger_00_prevent_physical_delete
BEFORE DELETE ON users
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_immutable_updates
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_01_enforce_users_versioning
BEFORE INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_02_trim_users_strings
BEFORE INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE trim_users_strings();

CREATE TRIGGER trigger_03_enforce_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

-- AFTER trigger to log the finalized row
CREATE TRIGGER trigger_04_log_user_version
AFTER INSERT OR UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE log_user_version();
