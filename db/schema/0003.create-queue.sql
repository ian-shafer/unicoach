CREATE TABLE jobs (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    created_at      TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    updated_at      TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    job_type        TEXT            NOT NULL,
    payload         JSONB           NOT NULL,
    status          TEXT            NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at    TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    locked_until    TIMESTAMPTZ     NULL,
    max_attempts    INTEGER         NULL,

    CONSTRAINT jobs_job_type_length_check CHECK (length(job_type) <= 128),
    CONSTRAINT jobs_status_length_check CHECK (length(status) <= 32),
    CONSTRAINT jobs_status_valid_check CHECK (
        status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'DEAD_LETTERED')
    ),
    CONSTRAINT jobs_payload_size_check CHECK (octet_length(payload::text) <= 65536)
);

CREATE INDEX idx_jobs_scheduled_job_type ON jobs (job_type, scheduled_at)
    WHERE status = 'SCHEDULED';
CREATE INDEX idx_jobs_status_locked_until ON jobs (status, locked_until)
    WHERE status = 'RUNNING';

CREATE TABLE job_attempts (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    job_id          UUID            NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number  INTEGER         NOT NULL,
    started_at      TIMESTAMPTZ     NOT NULL,
    finished_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    status          TEXT            NOT NULL,
    error_message   TEXT            NULL,

    CONSTRAINT job_attempts_status_length_check CHECK (length(status) <= 32),
    CONSTRAINT job_attempts_status_valid_check CHECK (
        status IN ('SUCCESS', 'RETRIABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    CONSTRAINT job_attempts_error_message_length_check CHECK (
        error_message IS NULL OR length(error_message) <= 4096
    ),

    UNIQUE(job_id, attempt_number)
);

CREATE INDEX idx_job_attempts_job_id ON job_attempts (job_id);

CREATE OR REPLACE FUNCTION update_jobs_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Maintain updated_at automatically on every UPDATE.
-- No version column on jobs, so enforce_versioning does not apply.
CREATE TRIGGER trigger_03_enforce_jobs_updated_at
BEFORE UPDATE ON jobs
FOR EACH ROW
EXECUTE PROCEDURE update_jobs_timestamp();
