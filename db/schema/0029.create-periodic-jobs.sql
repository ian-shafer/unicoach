-- Periodic tasks: a schedule table the :cron process claims from once a minute,
-- enqueuing each due row onto the existing queue as an ordinary job. RFC 97.
--
-- A mutable operational table modeled on jobs (0003.create-queue.sql): TEXT/JSONB
-- columns, length/size CHECKs, a two-timestamp split with an updated_at trigger.
-- It is NOT a domain entity — no uuidv7 id (the name is the natural key), no
-- soft-delete or physical-delete guards (rows are operator-managed config).
--
-- job_type is TEXT validated in the application (mapped through JobType.fromValue
-- at read time, exactly as jobs.job_type is); no SQL CHECK enumerates the queue's
-- JobType values, to avoid coupling the schema to the enum. schedule is the source
-- of truth; next_run_at is a value derived from it and must be recomputed whenever
-- schedule changes.

CREATE TABLE periodic_jobs (
    name         TEXT        NOT NULL PRIMARY KEY,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    job_type     TEXT        NOT NULL,   -- a queue JobType value to enqueue
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    schedule     TEXT        NOT NULL,   -- UNIX 5-field cron
    timezone     TEXT        NOT NULL DEFAULT 'UTC',
    next_run_at  TIMESTAMPTZ NOT NULL,   -- materialized next fire; the claim key
    last_run_at  TIMESTAMPTZ NULL,       -- audit: last time the row was claimed
    enabled      BOOLEAN     NOT NULL DEFAULT TRUE,

    CONSTRAINT periodic_jobs_name_length_check     CHECK (length(name) <= 128),
    CONSTRAINT periodic_jobs_job_type_length_check CHECK (length(job_type) <= 128),
    CONSTRAINT periodic_jobs_schedule_length_check CHECK (length(schedule) <= 256),
    CONSTRAINT periodic_jobs_timezone_length_check CHECK (length(timezone) <= 64),
    CONSTRAINT periodic_jobs_payload_size_check    CHECK (octet_length(payload::text) <= 65536)
);

-- Claim predicate: the due, enabled rows, cheapest next-fire first.
CREATE INDEX periodic_jobs_due_idx ON periodic_jobs (next_run_at) WHERE enabled;

-- updated_at maintenance, mirroring update_jobs_timestamp (0003).
CREATE OR REPLACE FUNCTION update_periodic_jobs_timestamp() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_03_enforce_periodic_jobs_updated_at
BEFORE UPDATE ON periodic_jobs
FOR EACH ROW
EXECUTE PROCEDURE update_periodic_jobs_timestamp();
