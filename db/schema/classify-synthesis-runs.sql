-- synthesis_runs: a failure reason, which may quote the model's output about the
-- person, is typed pii.*, and the log gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- failure_reason is already nullable. synthesis_runs_failure_pairing_check ties it
-- to failure_category, so it gains the erased arm: a deleted row may lose the
-- reason and keep the category.

ALTER TABLE synthesis_runs
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN failure_reason TYPE pii.text,
    DROP CONSTRAINT synthesis_runs_failure_pairing_check,
    ADD CONSTRAINT synthesis_runs_failure_pairing_check
        CHECK ((deleted_at IS NOT NULL AND failure_reason IS NULL)
               OR ((failure_category IS NULL) = (failure_reason IS NULL)));
