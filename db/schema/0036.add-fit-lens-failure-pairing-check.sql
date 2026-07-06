-- Backport the failure_category/failure_reason pairing CHECK onto the
-- already-committed fit_lens_runs (RFC 98, migration 0034). 0034's
-- fit_lens_runs_failure_consistency_check only forbids failure_category /
-- failure_reason both being set on a non-'failed' row — it does not forbid
-- one being set while the other is null. 0035 adds this pairing CHECK
-- upfront for extraction_runs/synthesis_runs; this migration closes the same
-- gap on fit_lens_runs.
--
-- fit_lens_runs_failure_consistency_check (0034) is left as-is — still
-- correct, now redundant with the pairing check, not worth a DROP/ADD dance
-- on a committed constraint for a pure strengthening.

ALTER TABLE fit_lens_runs ADD CONSTRAINT fit_lens_runs_failure_pairing_check
  CHECK ((failure_category IS NULL) = (failure_reason IS NULL));
