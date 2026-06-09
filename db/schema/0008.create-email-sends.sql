-- email_sends: append-only ledger of terminal transactional-email outcomes. RFC 34.
--
-- Shape: append-only log (postgres-log-table-design). One immutable row per
-- terminal send outcome, written once. Records terminal outcomes ONLY — SENT and
-- permanent REJECTED. Transient failures are never logged here (they are the
-- queue's domain, job_attempts); logging them would create two sources of truth
-- for one logical message.
--
-- The log-guard functions prevent_log_update() and prevent_log_delete() are
-- created in 0006 and, because migrations apply in lexical order, are present
-- before this migration runs; this file references them rather than redefining.

CREATE TABLE email_sends (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- logical send time
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- physical insert time

  recipient_email TEXT NOT NULL,  -- destination (EmailAddress.value)
  sender_email    TEXT NOT NULL,  -- configured sender (EmailAddress.value)
  subject         TEXT NOT NULL,
  body            TEXT NOT NULL,  -- plain-text body

  status   TEXT NOT NULL CHECK (status IN ('SENT', 'REJECTED')),  -- terminal outcome
  provider TEXT NOT NULL,  -- adapter identity, e.g. log-only (intentionally unconstrained; adapter set is open-ended)

  provider_message_id TEXT NULL,  -- provider's id; set when SENT
  error_message       TEXT NULL   -- rejection reason; set when REJECTED
);

CREATE TRIGGER trigger_00_prevent_email_sends_update
BEFORE UPDATE ON email_sends
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_email_sends_delete
BEFORE DELETE ON email_sends
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
