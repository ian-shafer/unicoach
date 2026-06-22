-- RFC 64: record how each session authenticated.
--
-- login_method is NULL exactly for anonymous (pre-auth) sessions and non-null
-- for user-bound sessions. The paired presence_check ties login_method to
-- user_id so an authenticated session can never lack a method and an anonymous
-- one can never carry one.

ALTER TABLE sessions ADD COLUMN login_method TEXT NULL;

-- Backfill existing authenticated sessions before adding the paired constraint.
UPDATE sessions SET login_method = 'password' WHERE user_id IS NOT NULL;

ALTER TABLE sessions
  ADD CONSTRAINT sessions_login_method_check CHECK (login_method IN ('password', 'google')),
  ADD CONSTRAINT sessions_login_method_presence_check
    CHECK ((user_id IS NULL) = (login_method IS NULL));
