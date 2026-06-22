-- RFC 64: remove the single-credential AuthMethod model from users.
--
-- The federated credential moves entirely to user_auth_identities (0015); the
-- password credential remains on the row as the nullable password_hash column.
-- The "at least one auth method" guarantee can no longer be a row-local CHECK
-- (an SSO-only user's sole credential is a row in another table), so the
-- users_auth_method_check is dropped and the guarantee becomes an application
-- invariant in AuthService.

-- Guard: refuse to drop the column if any row still carries an sso_provider_id,
-- which would silently strip a user's only credential. Pre-launch there are no
-- such rows; this fails loudly if that assumption is ever wrong.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM users WHERE sso_provider_id IS NOT NULL) THEN
    RAISE EXCEPTION 'Cannot drop sso_provider_id: % users still hold an SSO credential',
      (SELECT count(*) FROM users WHERE sso_provider_id IS NOT NULL);
  END IF;
END $$;

ALTER TABLE users DROP CONSTRAINT users_auth_method_check;
ALTER TABLE users DROP CONSTRAINT users_sso_provider_id_length_check;
ALTER TABLE users DROP CONSTRAINT users_sso_provider_id_not_empty_check;

DROP INDEX users_sso_provider_id_idx;

ALTER TABLE users DROP COLUMN sso_provider_id;
ALTER TABLE users_versions DROP COLUMN sso_provider_id;

-- Recreate the version-logging trigger function with sso_provider_id removed but
-- every other current column preserved (the prior column list from 0013 minus
-- sso_provider_id), notably is_admin and email_verified_at.
CREATE OR REPLACE FUNCTION log_user_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO users_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        email, name, display_name, password_hash, is_admin, email_verified_at
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.email, NEW.name, NEW.display_name, NEW.password_hash, NEW.is_admin, NEW.email_verified_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
