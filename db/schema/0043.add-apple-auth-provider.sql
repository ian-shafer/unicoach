-- RFC 111: widen the provider/login-method CHECK constraints to admit 'apple'
-- alongside the existing 'google'. Nothing else about user_auth_identities or
-- sessions changes (shape, indexes, triggers are untouched).

ALTER TABLE user_auth_identities
  DROP CONSTRAINT user_auth_identities_provider_check,
  ADD CONSTRAINT user_auth_identities_provider_check
    CHECK (provider IN ('google', 'apple'));

ALTER TABLE sessions
  DROP CONSTRAINT sessions_login_method_check,
  ADD CONSTRAINT sessions_login_method_check
    CHECK (login_method IN ('password', 'google', 'apple'));
