CREATE TABLE sessions (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,
  
  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  
  -- Session Data
  user_id UUID NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash BYTEA NOT NULL,
  user_agent TEXT NULL,
  initial_ip TEXT NULL,
  metadata JSONB NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  is_revoked BOOLEAN NOT NULL DEFAULT false,
  
  -- Constraints
  CONSTRAINT sessions_user_agent_length_check CHECK (user_agent IS NULL OR length(user_agent) <= 512),
  CONSTRAINT sessions_initial_ip_length_check CHECK (initial_ip IS NULL OR length(initial_ip) <= 64),
  CONSTRAINT sessions_metadata_size_check CHECK (metadata IS NULL OR pg_column_size(metadata) <= 2048)
);

CREATE UNIQUE INDEX sessions_token_hash_idx ON sessions (token_hash);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);

-- Set up standard entity behavior
CREATE TRIGGER trigger_00a_prevent_immutable_updates
BEFORE UPDATE ON sessions
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_01_enforce_sessions_versioning
BEFORE INSERT OR UPDATE ON sessions
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_sessions_updated_at
BEFORE UPDATE ON sessions
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();
