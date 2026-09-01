-- RFC 155: the student's revocable share link for the Family Cost Report.
-- A hashed credential, not a versioned aggregate: its only mutation is setting
-- revoked_at once, guarded by a compare-and-swap UPDATE, so no OCC version
-- column and no _versions history are needed. Modeled on verification_tokens
-- (0014) -- only the SHA-256 hash is stored; the raw token exists only in the
-- link the student sends. Unlike a verification token this one is long-lived
-- and multi-use: there is no expires_at and no consumed_at, because a parent
-- re-opens the link months later when the aid offers arrive. Revocation is the
-- control, and it is student-driven and immediate.
--
-- The raw token is DERIVED, not random: HMAC-SHA256(shareTokenSecret, id), so a
-- re-share reproduces the same link from this row while the database still holds
-- nothing that grants access. Rotating the secret is a global revoke.

CREATE TABLE cost_report_shares (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID  NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  token_hash BYTEA NOT NULL,            -- SHA-256 of the raw token; raw never stored
  revoked_at TIMESTAMPTZ NULL           -- set once; a revoked link is dead forever
);

CREATE UNIQUE INDEX cost_report_shares_token_hash_idx
  ON cost_report_shares (token_hash);

-- At most one live share per student (RFC 155 D-B): re-sharing returns the same
-- link, and "revoke" is a promise about every link ever sent, not the latest one.
CREATE UNIQUE INDEX cost_report_shares_one_live_per_student_idx
  ON cost_report_shares (student_id) WHERE revoked_at IS NULL;
