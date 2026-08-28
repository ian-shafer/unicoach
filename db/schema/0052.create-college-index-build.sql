-- Ingest provenance: one row per successful bin/ingest-colleges run. RFC 139.
--
-- Derived/operational table (0004 gate-2 shape): no versioning, no soft delete,
-- append-only by convention — each run inserts exactly one row at the very end,
-- success paths only; a failed ingest writes no row and exits non-zero. The
-- `index_rows` column arrives with the S3 derived table and is NULL until then.
--
--   sources        — [{file, sha256, bytes, source_arg}] per source file
--   rows_ingested  — per table: inserted/changed/unchanged/skipped counts
--   change_summary — per column: non-null counts before/after; version bumps
--   method_version — bumped when the derivation logic changes (1 = RFC 139)

CREATE TABLE college_index_build (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    sources        JSONB       NOT NULL,
    rows_ingested  JSONB       NOT NULL,
    index_rows     INTEGER     NULL,
    change_summary JSONB       NOT NULL,
    method_version INTEGER     NOT NULL,
    CONSTRAINT college_index_build_finished_after_started_check
        CHECK (finished_at >= started_at),
    CONSTRAINT college_index_build_method_version_positive_check
        CHECK (method_version > 0),
    CONSTRAINT college_index_build_index_rows_nonneg_check
        CHECK (index_rows IS NULL OR index_rows >= 0)
);
