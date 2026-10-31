-- fit_lens_runs: a failure reason, which may quote the model's output about
-- the person, is typed pii.*, and the log gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- failure_reason is already nullable. fit_lens_runs_failure_pairing_check ties it
-- to failure_category and gains the erased arm, so a deleted failed run may lose
-- the reason and keep its category.
--
-- fit_lens_runs_failure_consistency_check (0034) tied the outcome to category AND
-- reason. An erased arm on it would short-circuit the outcome-to-category tie as
-- well, and its rule as today admits a non-failed run with a category and no
-- reason (only the pairing rule, which the erased arm frees, refused that). It is
-- restated in extraction_runs' and synthesis_runs' form (0035), which names no
-- personal column and so needs no arm: on a live row it admits exactly what the
-- old rule did, because the pairing rule ties the reason to the category; on a
-- deleted row it keeps failed-iff-categorised.

ALTER TABLE fit_lens_runs
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN failure_reason TYPE pii.text,
    DROP CONSTRAINT fit_lens_runs_failure_pairing_check,
    DROP CONSTRAINT fit_lens_runs_failure_consistency_check,
    ADD CONSTRAINT fit_lens_runs_failure_pairing_check
        CHECK ((deleted_at IS NOT NULL AND failure_reason IS NULL)
               OR ((failure_category IS NULL) = (failure_reason IS NULL))),
    ADD CONSTRAINT fit_lens_runs_failure_consistency_check
        CHECK ((outcome = 'failed') = (failure_category IS NOT NULL));
