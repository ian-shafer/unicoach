-- sessions.metadata now holds a typed SessionMetadata (session_metadata.proto)
-- as canonical proto3 JSON: a CSRF token and signed-in form drafts (RFC 265,
-- brief 0013 D4/D4b/D22). The 2048-byte cap predates any writer; the largest
-- planned draft measures 8774 bytes, and the cap rises to 256 KiB (RFC 265,
-- "The size cap: measured"). Column type unchanged: JSONB stays queryable.
ALTER TABLE sessions
  DROP CONSTRAINT sessions_metadata_size_check,
  ADD CONSTRAINT sessions_metadata_size_check
    CHECK (metadata IS NULL OR pg_column_size(metadata) <= 262144);
