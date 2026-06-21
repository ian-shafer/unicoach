-- RFC 65: Single-use email-verification credential table.
-- Neither a versioned aggregate nor an append-only log: its only mutation is
-- setting consumed_at exactly once, guarded by a compare-and-swap UPDATE, so no
-- OCC version column and no _versions history are needed. Modeled on sessions (a
-- hashed credential): only the SHA-256 hash of the token is stored; the raw token
-- exists only in the email link.

CREATE TABLE verification_tokens (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  user_id    UUID NOT NULL REFERENCES users(id),
  token_hash BYTEA NOT NULL,          -- SHA-256 of the raw token; raw never stored
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ NULL        -- single-use marker; set once on verify
);

CREATE UNIQUE INDEX verification_tokens_token_hash_idx ON verification_tokens (token_hash);
CREATE INDEX verification_tokens_user_id_idx ON verification_tokens (user_id);
