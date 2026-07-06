-- Add extraction_runs/synthesis_runs.failure_category / failure_reason: the
-- cause of a 'failed' pass, previously logged only (WARN in
-- ExtractionService/SynthesisService) and dropped before the DB write. Mirrors
-- fit_lens_runs' failure_category/failure_reason (RFC 98, migration 0034).
--
-- One migration, both tables: ExtractionService/SynthesisService's private
-- ParseFailure shapes are byte-for-byte identical (NotAnObject /
-- MalformedJson(detail) / BadField(field, value)), so two near-duplicate
-- migration files would just be copy-paste.
--
-- failure_category is a closed enum (TEXT + CHECK, project convention) — a 1:1
-- mirror of ParseFailure's three variants, not fit_lens_runs' coarser
-- malformed_output/invalid_content split: 'not_a_json_object' (the root
-- wasn't a JSON object at all), 'malformed_json' (the text didn't parse as
-- JSON), 'invalid_field' (it parsed, but a field was missing, wrong-shape, or
-- failed enum membership). BadField doesn't yet distinguish a wrong-shape
-- field from a right-shape-wrong-value one; that finer split is a deferred
-- follow-up, not attempted here. failure_reason is the free-text diagnostic
-- (the offending field/value, a raw JSON error) the category alone would
-- lose.
--
-- Both null on an 'applied' row; both required on a 'failed' row. Unlike
-- 0034, the pairing CHECK ((failure_category IS NULL) = (failure_reason IS
-- NULL)) is added upfront alongside the single-column consistency CHECK,
-- closing a gap 0034 left open (see 0036).

ALTER TABLE extraction_runs
  ADD COLUMN failure_category TEXT NULL,
  ADD COLUMN failure_reason   TEXT NULL;

ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_category_check
  CHECK (failure_category IN ('not_a_json_object','malformed_json','invalid_field'));

ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_reason_length_check
  CHECK (failure_reason IS NULL OR length(failure_reason) <= 2048);

-- Closes a gap the outcome-tie check alone leaves open: without this, a
-- non-failed row could carry one column set and the other null.
ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));

-- With pairing enforced above, this only needs to reference one column.
ALTER TABLE extraction_runs ADD CONSTRAINT extraction_runs_failure_consistency_check
  CHECK ((outcome = 'failed') = (failure_category IS NOT NULL));

ALTER TABLE synthesis_runs
  ADD COLUMN failure_category TEXT NULL,
  ADD COLUMN failure_reason   TEXT NULL;

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_category_check
  CHECK (failure_category IN ('not_a_json_object','malformed_json','invalid_field'));

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_reason_length_check
  CHECK (failure_reason IS NULL OR length(failure_reason) <= 2048);

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));

ALTER TABLE synthesis_runs ADD CONSTRAINT synthesis_runs_failure_consistency_check
  CHECK ((outcome = 'failed') = (failure_category IS NOT NULL));
