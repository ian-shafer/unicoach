-- Seed the synthesis sweep as the first periodic job. RFC 97.
--
-- '0 3 * * *' = 03:00 UTC daily; next_run_at seeds to the next 03:00 UTC boundary
-- (not NOW(), which would fire on the first tick after deploy), and the scheduler
-- owns the column thereafter.
--
-- The row seeds enabled = FALSE so the sweep never fires against an environment
-- whose worker has synthesis off (its SYNTHESIZE_STUDENT jobs would hit "no
-- handler registered"). An operator flips enabled to TRUE via the admin toggle in
-- the same environments where synthesis.enabled = true gates the worker's handler
-- registration.

INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
VALUES (
  'synthesis', 'SYNTHESIS_SWEEP', '{}', '0 3 * * *', 'UTC',
  date_trunc('day', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 day 3 hours',
  FALSE
);
