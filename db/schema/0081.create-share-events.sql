-- RFC 160: share_events -- the append-only log of what the Family Cost Report
-- share surface does. cost_report_shares (0073) is a mutable credential table,
-- not a history: a repeat ask touches nothing, so it was invisible, and a
-- reissue looks like an unrelated revoke+mint. Each row here is one immutable
-- fact recorded IN THE SAME TRANSACTION as the share mutation it describes
-- (or, for 'opted_out', as the student's durable "never ask me again", recorded
-- from chat with no share row by nature). Standard log-table design
-- (synthesis_runs/commitment_support precedent): BIGINT identity, created_at,
-- no updated_at, guard triggers reusing the shared prevent_log_* functions.
--
-- Kinds:
--   minted    -- a first live link (or the first after a student-driven revoke);
--                names the new row.
--   repeat    -- the student asked again and got the SAME live link back;
--                names the existing live row (the previously invisible case).
--   reissued  -- the secret rotated, so the stale row was revoked inside the
--                reissue and a new row minted; names the NEW row. The interior
--                revocation is part of the reissue, not a separate 'revoked'.
--   revoked   -- the student's live share was revoked; names the revoked row.
--   opted_out -- "never suggest sharing again": the one kind with no share row,
--                hence the paired CHECK. Read by the synthesis share-nudge step
--                as a permanent suppression; never expired, never undone in app
--                code (an operator can act in the database if a student
--                genuinely recants).
--
-- ON DELETE CASCADE on both FKs: students are only ever soft-deleted in app
-- code, and a share row is never physically deleted either, so the cascades
-- exist for operator-driven hard deletes (the same posture as synthesis_runs).

CREATE TABLE share_events (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  share_id   UUID NULL REFERENCES cost_report_shares(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,
  CONSTRAINT share_events_kind_check
    CHECK (kind IN ('minted','repeat','reissued','revoked','opted_out')),
  -- Every share-surface kind names its concrete share row; 'opted_out' is the
  -- one kind that has none. Stated as an equality so BOTH illegal shapes -- a
  -- shareless 'minted' and a share-bearing 'opted_out' -- are unrepresentable.
  CONSTRAINT share_events_share_id_check
    CHECK ((kind = 'opted_out') = (share_id IS NULL))
);

-- The hot reads: the nudge step's opt-out probe and Beat 2's per-student history.
CREATE INDEX share_events_student_idx ON share_events (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_share_events_update
BEFORE UPDATE ON share_events FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_share_events_delete
BEFORE DELETE ON share_events FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
