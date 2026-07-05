-- Seed the fit-lens sweep as a periodic job (RFC 98, over RFC 97's periodic_jobs
-- table). Mirrors 0030.seed-synthesis-periodic-job.sql.
--
-- '0 4 * * 1' = 04:00 UTC every Monday (weekly, slower than synthesis''s daily);
-- next_run_at seeds to the next Monday 04:00 UTC boundary (not NOW(), which would
-- fire on the first tick after deploy), and RFC 97''s scheduler owns the column
-- thereafter.
--
-- The row seeds enabled = FALSE so the sweep never fires against an environment
-- whose worker has fit-lens off (its FIT_LENS jobs would hit "no handler
-- registered"). An operator flips enabled to TRUE via the admin toggle in the
-- same environments where fitLens.enabled = true gates the worker''s handler
-- registration, coupled by convention exactly as synthesis is.

INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
VALUES (
  'fit-lens', 'FIT_LENS_SWEEP', '{}', '0 4 * * 1', 'UTC',
  date_trunc('week', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 week 4 hours',
  FALSE
);
