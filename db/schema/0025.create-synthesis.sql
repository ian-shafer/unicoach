-- Synthesis: a per-student background reflection pass writing coach-owned
-- commitments derived from the student's accumulated model. RFC 93.
--
-- Three tables:
--   commitments        — mutable entity: a coach-owned intention with a
--                         status-based lifecycle (open -> fulfilled | dropped),
--                         modeled exactly on claims (no versioning, no deleted_at).
--   commitment_support — append-only link log: many-to-many commitments<->claims,
--                        mirroring claim_support exactly.
--   synthesis_runs     — append-only log: token ledger + provenance + the
--                        student's synthesis freshness marker.
--
-- All reuse the shared guard functions from prior migrations
-- (prevent_physical_delete, prevent_immutable_updates,
-- prevent_physical_timestamp_update, update_timestamp, prevent_log_update,
-- prevent_log_delete) with no new function. Closed enums are TEXT + named CHECK
-- (project convention). PostgreSQL 18; uuidv7() is built-in.

-- ---------------------------------------------------------------------------
-- commitments — mutable entity
--
-- A coach-owned intention derived from reflection over the student's model. It
-- resolves from 'open' to 'fulfilled' (surfaced to the student as a promise
-- kept) or 'dropped' (its basis went away). Modeled exactly on claims: the
-- four-timestamp split, no versioning, no deleted_at. 'trigger' is a SQL
-- reserved word, so the surfacing-condition column is named 'trigger_kind'.
-- ---------------------------------------------------------------------------

CREATE TABLE commitments (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  lens       TEXT NOT NULL,                     -- gap | timing | contradiction
  disclosure TEXT NOT NULL,                     -- explicit | internal
  status     TEXT NOT NULL DEFAULT 'open',      -- open | fulfilled | dropped

  statement    TEXT NOT NULL,                   -- the coach's intention, free text
  trigger_kind TEXT NOT NULL DEFAULT 'next_session', -- surfacing condition
  trigger_at   TIMESTAMPTZ NULL,                -- advisory date the insight references (timing); recorded, never acted on (no scheduler)

  fulfilled_at          TIMESTAMPTZ NULL,
  disclosed_in_convo_id UUID        NULL REFERENCES convos(id) ON DELETE RESTRICT, -- convos are never physically deleted (prevent_physical_delete), so RESTRICT never fires
  dropped_at            TIMESTAMPTZ NULL,
  drop_reason           TEXT        NULL,

  CONSTRAINT commitments_lens_check         CHECK (lens IN ('gap','timing','contradiction')),
  CONSTRAINT commitments_disclosure_check   CHECK (disclosure IN ('explicit','internal')),
  CONSTRAINT commitments_status_check       CHECK (status IN ('open','fulfilled','dropped')),
  CONSTRAINT commitments_trigger_kind_check CHECK (trigger_kind IN ('next_session')),
  CONSTRAINT commitments_statement_length_check     CHECK (length(statement) <= 2048),
  CONSTRAINT commitments_statement_not_empty_check  CHECK (length(trim(statement)) > 0),
  CONSTRAINT commitments_drop_reason_length_check   CHECK (drop_reason IS NULL OR length(drop_reason) <= 255),
  -- Lifecycle consistency: fulfilled iff surfaced into a convo; dropped iff timestamped.
  -- Together these force an 'open' row to have all three resolution columns NULL.
  CONSTRAINT commitments_fulfilled_consistency_check CHECK (
    (status = 'fulfilled') = (fulfilled_at IS NOT NULL AND disclosed_in_convo_id IS NOT NULL)
  ),
  CONSTRAINT commitments_dropped_consistency_check CHECK (
    (status = 'dropped') = (dropped_at IS NOT NULL)
  )
);

-- The opener read (delivery): open explicit commitments for a student.
CREATE INDEX commitments_student_open_explicit_idx
  ON commitments (student_id) WHERE status = 'open' AND disclosure = 'explicit';
-- Synthesis open-set read + admin filtering.
CREATE INDEX commitments_student_status_idx ON commitments (student_id, status);

CREATE TRIGGER trigger_00_prevent_commitments_physical_delete
BEFORE DELETE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_commitments_immutable_updates
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();
CREATE TRIGGER trigger_03_enforce_commitments_updated_at
BEFORE UPDATE ON commitments FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

-- ---------------------------------------------------------------------------
-- commitment_support — append-only link log
--
-- The many-to-many link from a commitment to the claims it reasoned over. Each
-- row is the immutable fact "this claim was cited as basis for this commitment."
-- The trail to the utterance is preserved transitively through claim_support
-- (commitment -> claim -> observation). A commitment with no support rows is a
-- whole-model inference (e.g. a gap), exactly as most claims cite no observation.
-- ---------------------------------------------------------------------------

CREATE TABLE commitment_support (
  commitment_id UUID NOT NULL REFERENCES commitments(id) ON DELETE CASCADE,
  claim_id      UUID NOT NULL REFERENCES claims(id)      ON DELETE CASCADE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (commitment_id, claim_id)
);

-- Reverse lookup: "which commitments does this claim back" (stale-drop + admin).
CREATE INDEX commitment_support_claim_idx ON commitment_support (claim_id);

CREATE TRIGGER trigger_00_prevent_commitment_support_update
BEFORE UPDATE ON commitment_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_commitment_support_delete
BEFORE DELETE ON commitment_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- synthesis_runs — append-only log (token ledger + provenance + freshness marker)
--
-- One row per billed synthesis LLM call over a student — success or failure. It
-- mirrors extraction_runs and serves three jobs: the student's synthesis
-- freshness marker (MAX(created_at) WHERE outcome = 'applied'); the provenance
-- of the call (prompt pin, model); and the per-student token ledger, so every
-- token spent on a student is recorded even when the pass fails and retries.
-- ---------------------------------------------------------------------------

CREATE TABLE synthesis_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- 'applied' wrote commitments and advances the freshness marker; 'failed'
  -- billed tokens but produced unusable output (marker unchanged, counts zero).
  outcome TEXT NOT NULL,

  system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider         TEXT NOT NULL,
  model_resolved   TEXT NULL,

  commitments_written INTEGER NOT NULL DEFAULT 0,
  commitments_dropped INTEGER NOT NULL DEFAULT 0,

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT synthesis_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT synthesis_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT synthesis_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT synthesis_runs_failed_counts_check CHECK (
    outcome <> 'failed' OR (commitments_written = 0 AND commitments_dropped = 0)
  ),
  CONSTRAINT synthesis_runs_counts_nonneg_check CHECK (
    commitments_written >= 0 AND commitments_dropped >= 0
  ),
  CONSTRAINT synthesis_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

-- Freshness marker: latest applied run per student.
CREATE INDEX synthesis_runs_student_applied_idx
  ON synthesis_runs (student_id, created_at) WHERE outcome = 'applied';
-- Per-student token-accounting scan.
CREATE INDEX synthesis_runs_student_idx ON synthesis_runs (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_synthesis_runs_update
BEFORE UPDATE ON synthesis_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_synthesis_runs_delete
BEFORE DELETE ON synthesis_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
