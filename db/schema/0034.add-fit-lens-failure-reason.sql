-- Add fit_lens_runs.failure_category / failure_reason: the cause of a 'failed'
-- pass, previously logged only (WARN in FitLensService) and dropped before the
-- DB write. Unrelated to RFC 98's deferred token-accounting table (that gap is
-- per-call token grain; this is about a diagnostic that was never persisted at
-- all).
--
-- Two columns, not one: failure_category is a closed enum (TEXT + CHECK,
-- project convention), deliberately coarse (HTTP-status-code style: broad
-- classes, not one entry per parse site) — 'malformed_output' (the raw output
-- wasn't valid/parseable JSON matching the expected shape) vs
-- 'invalid_content' (it parsed fine, but violated a business rule). A more
-- specific category is only worth adding once volume on a given failure shape
-- shows it's common enough to warrant its own bucket. failure_reason is the
-- free-text diagnostic detail (the offending collegeId, a length count, a raw
-- JSON error) that the category alone would lose. Both null on an 'applied'
-- run; both required on a 'failed' run, mirroring
-- fit_lens_runs_failed_counts_check's outcome-gated shape.

ALTER TABLE fit_lens_runs
  ADD COLUMN failure_category TEXT NULL,
  ADD COLUMN failure_reason   TEXT NULL;

ALTER TABLE fit_lens_runs ADD CONSTRAINT fit_lens_runs_failure_category_check
  CHECK (failure_category IN (
    'malformed_output',
    'invalid_content'
  ));

ALTER TABLE fit_lens_runs ADD CONSTRAINT fit_lens_runs_failure_reason_length_check
  CHECK (failure_reason IS NULL OR length(failure_reason) <= 2048);

ALTER TABLE fit_lens_runs ADD CONSTRAINT fit_lens_runs_failure_consistency_check
  CHECK ((outcome = 'failed') = (failure_category IS NOT NULL AND failure_reason IS NOT NULL));
