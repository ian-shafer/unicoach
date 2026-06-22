-- RFC 64: user_auth_identities — append-only log of federated login identities.
--
-- Shape: append-only log (postgres-log-table-design). One immutable row records
-- the fact that a federated (provider, subject) belongs to a user, established
-- at a point in time. Rows are inserted once and never updated or deleted by
-- application code, so the table carries no version, updated_at, deleted_at, or
-- version-history table.
--
-- email / email_verified are provenance only — the claims Google asserted at row
-- creation. They are never re-synced and never read on the login path; lookup is
-- strictly by (provider, subject). They carry no uniqueness or FK guarantee.
--
-- The log-guard functions prevent_log_update() and prevent_log_delete() are
-- created in 0006 and present before this migration runs (lexical order); this
-- file references them rather than redefining.

CREATE TABLE user_auth_identities (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,     -- logical fact time
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL, -- physical insert time
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  subject TEXT NOT NULL,
  email TEXT NOT NULL,
  email_verified BOOLEAN NOT NULL,
  CONSTRAINT user_auth_identities_provider_check CHECK (provider IN ('google')),
  CONSTRAINT user_auth_identities_subject_length_check CHECK (length(subject) <= 255),
  CONSTRAINT user_auth_identities_subject_not_empty_check CHECK (length(trim(subject)) > 0),
  CONSTRAINT user_auth_identities_email_length_check CHECK (length(email) <= 254),
  CONSTRAINT user_auth_identities_email_not_empty_check CHECK (length(trim(email)) > 0),
  CONSTRAINT user_auth_identities_email_lowercase_check CHECK (email = LOWER(email)),
  CONSTRAINT user_auth_identities_email_format_check CHECK (email LIKE '%@%')
);

-- At most one user per federated identity. Does not restrict a user to one
-- identity per provider (distinct subjects may link to the same user).
CREATE UNIQUE INDEX user_auth_identities_provider_subject_idx
  ON user_auth_identities (provider, subject);
CREATE INDEX user_auth_identities_user_id_idx
  ON user_auth_identities (user_id);

-- Append-only guards. The BEFORE DELETE guard and the ON DELETE CASCADE FK
-- coexist harmlessly: the parent users row is prevent_physical_delete-protected
-- and only ever soft-deleted, so the cascade never fires.
CREATE TRIGGER trigger_00_prevent_user_auth_identities_update
BEFORE UPDATE ON user_auth_identities
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_user_auth_identities_delete
BEFORE DELETE ON user_auth_identities
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
