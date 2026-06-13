-- Add the archive state the Conversation contract (api-specs/openapi.yaml)
-- exposes as `archivedAt`. RFC 45.
--
-- `archived_at IS NOT NULL` = archived (reversible). It is an independent axis
-- from `deleted_at` (soft-delete, terminal for the API). The partial index
-- convos_student_id_idx (... WHERE deleted_at IS NULL) continues to serve both
-- listing filters. prevent_immutable_updates does not cover this column, so it
-- is mutable as required by the archive/unarchive toggle.

ALTER TABLE convos
  ADD COLUMN archived_at TIMESTAMPTZ NULL;
