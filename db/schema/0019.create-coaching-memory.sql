-- Coaching memory substrate: the durable structure extraction distills from a
-- conversation's transcript. RFC 66.
--
-- Four tables:
--   observations    — append-only log: immutable records of what a student said.
--   claims          — mutable entity: the coach's current, revisable belief.
--   claim_support   — append-only link log: many-to-many claims<->observations.
--   extraction_runs — append-only log: provenance + watermark + token ledger.
--
-- All reuse the shared guard functions defined by prior migrations
-- (prevent_log_update, prevent_log_delete, prevent_immutable_updates,
-- prevent_physical_delete, update_timestamp). Closed enums are TEXT + named
-- CHECK (project convention, not native pg enums). PostgreSQL 18; uuidv7() is
-- built-in.

-- ---------------------------------------------------------------------------
-- observations — append-only log
--
-- Immutable records of what a student said. The quote span is verbatim; an
-- observation is true forever, so belief revision happens only at the claims
-- layer, never here.
-- ---------------------------------------------------------------------------

CREATE TABLE observations (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),         -- ingest time

  student_id        UUID   NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  convo_id          UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,
  source_request_id BIGINT NOT NULL REFERENCES convo_requests(id) ON DELETE CASCADE,
  uttered_at        TIMESTAMPTZ NOT NULL,                -- event time (= the source turn's created_at)

  quote TEXT NOT NULL,                                   -- verbatim span the student said

  CONSTRAINT observations_quote_length_check    CHECK (length(quote) <= 4096),
  CONSTRAINT observations_quote_not_empty_check CHECK (length(trim(quote)) > 0)
);

CREATE INDEX observations_student_id_created_at_idx ON observations (student_id, created_at);
CREATE INDEX observations_convo_source_idx          ON observations (convo_id, source_request_id);

CREATE TRIGGER trigger_00_prevent_observations_update
BEFORE UPDATE ON observations FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_observations_delete
BEFORE DELETE ON observations FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- claims — mutable entity
--
-- The coach's current belief about a student, distilled from observations
-- and/or reasoned across other claims. Revisable: it gains confidence, is
-- superseded by a newer belief, or is retracted, while its supporting
-- observations stay immutable.
-- ---------------------------------------------------------------------------

CREATE TABLE claims (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  origin     TEXT NOT NULL,                       -- student_stated | coach_inferred
  status     TEXT NOT NULL DEFAULT 'active',      -- active | superseded | retracted
  kind       TEXT NOT NULL,                       -- goal | preference | constraint | fact | concern
  subject    TEXT NOT NULL,                       -- student | family | college | application
  topic      TEXT NOT NULL,                       -- academics | activities | finances | location | career | timeline | wellbeing
  visibility TEXT NOT NULL DEFAULT 'student_visible', -- student_visible | internal

  statement  TEXT NOT NULL,                       -- the belief, free text
  confidence INTEGER NOT NULL DEFAULT 0,          -- 0..1000 fixed-point; code-recomputed (see below)

  superseded_by_id UUID        NULL REFERENCES claims(id) ON DELETE RESTRICT, -- claims are never physically deleted (prevent_physical_delete), so RESTRICT never fires
  superseded_at    TIMESTAMPTZ NULL,
  retracted_at     TIMESTAMPTZ NULL,

  CONSTRAINT claims_origin_check     CHECK (origin IN ('student_stated','coach_inferred')),
  CONSTRAINT claims_status_check     CHECK (status IN ('active','superseded','retracted')),
  CONSTRAINT claims_kind_check       CHECK (kind IN ('goal','preference','constraint','fact','concern')),
  CONSTRAINT claims_subject_check    CHECK (subject IN ('student','family','college','application')),
  CONSTRAINT claims_topic_check      CHECK (topic IN ('academics','activities','finances','location','career','timeline','wellbeing')),
  CONSTRAINT claims_visibility_check CHECK (visibility IN ('student_visible','internal')),
  CONSTRAINT claims_statement_length_check    CHECK (length(statement) <= 2048),
  CONSTRAINT claims_statement_not_empty_check CHECK (length(trim(statement)) > 0),
  CONSTRAINT claims_confidence_range_check    CHECK (confidence BETWEEN 0 AND 1000),
  CONSTRAINT claims_not_self_superseded_check CHECK (superseded_by_id IS NULL OR superseded_by_id <> id),
  -- Lifecycle consistency: superseded iff it points at its successor; retracted iff timestamped.
  CONSTRAINT claims_superseded_consistency_check CHECK (
    (status = 'superseded') = (superseded_by_id IS NOT NULL AND superseded_at IS NOT NULL)
  ),
  CONSTRAINT claims_retracted_consistency_check CHECK (
    (status = 'retracted') = (retracted_at IS NOT NULL)
  )
);

-- Active-belief injection ("what does the coach currently believe about X")
-- is the hot read; index it partially.
CREATE INDEX claims_student_active_idx ON claims (student_id) WHERE status = 'active';
CREATE INDEX claims_student_status_idx ON claims (student_id, status);

CREATE TRIGGER trigger_00_prevent_claims_physical_delete
BEFORE DELETE ON claims FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_claims_immutable_updates
BEFORE UPDATE ON claims FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_03_enforce_claims_updated_at
BEFORE UPDATE ON claims FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

-- ---------------------------------------------------------------------------
-- claim_support — append-only link log
--
-- The many-to-many link from claims to the observations backing them. Each row
-- is an immutable fact: "this observation was cited as support for this claim."
-- A claim with no claim_support rows is a pure cross-claim inference
-- (coach_inferred).
-- ---------------------------------------------------------------------------

CREATE TABLE claim_support (
  claim_id       UUID   NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
  observation_id BIGINT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (claim_id, observation_id)
);

-- Reverse lookup: "which claims does this observation support".
CREATE INDEX claim_support_observation_idx ON claim_support (observation_id);

CREATE TRIGGER trigger_00_prevent_claim_support_update
BEFORE UPDATE ON claim_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_claim_support_delete
BEFORE DELETE ON claim_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- extraction_runs — append-only log (provenance + watermark + token ledger)
--
-- One row per billed extraction LLM call over a conversation — success or
-- failure. It serves three jobs: the conversation's extraction watermark
-- (MAX(through_request_id) WHERE outcome = 'applied'); the provenance of the
-- call that produced the pass's claims (model, prompt pin); and the per-pass
-- token ledger, so every token spent on a student is recorded even when the
-- pass fails and retries.
-- ---------------------------------------------------------------------------

CREATE TABLE extraction_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Stream + per-user accounting. student_id is denormalized from the convo
  -- (immutable on a convo), so "tokens spent on this student" is a single-table scan.
  convo_id           UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,
  student_id         UUID   NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  through_request_id BIGINT NOT NULL REFERENCES convo_requests(id) ON DELETE CASCADE, -- window target attempted; counts toward the watermark only when outcome = 'applied'

  -- Every billed LLM call writes exactly one row. 'applied' advanced the watermark
  -- and wrote memory; 'failed' billed tokens but produced unusable output
  -- (watermark unchanged, write counts zero).
  outcome TEXT NOT NULL,

  -- Provenance of the distillation call (always present — every row is one call).
  system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider         TEXT NOT NULL,
  model_resolved   TEXT NULL,

  observations_written INTEGER NOT NULL DEFAULT 0,
  claims_written       INTEGER NOT NULL DEFAULT 0,
  claims_superseded    INTEGER NOT NULL DEFAULT 0,

  -- Token usage, recorded for every billed call (success OR failure). Mirrors the
  -- four-column shape of convo_responses so chat + extraction spend are summable.
  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT extraction_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT extraction_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT extraction_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  -- A failed call wrote no memory.
  CONSTRAINT extraction_runs_failed_counts_check CHECK (
    outcome <> 'failed' OR (observations_written = 0 AND claims_written = 0 AND claims_superseded = 0)
  ),
  CONSTRAINT extraction_runs_counts_nonneg_check CHECK (
    observations_written >= 0 AND claims_written >= 0 AND claims_superseded >= 0
  ),
  CONSTRAINT extraction_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

-- Watermark: highest applied target per conversation.
CREATE INDEX extraction_runs_convo_watermark_idx
  ON extraction_runs (convo_id, through_request_id) WHERE outcome = 'applied';
-- Per-user token-accounting scan.
CREATE INDEX extraction_runs_student_idx ON extraction_runs (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_extraction_runs_update
BEFORE UPDATE ON extraction_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_extraction_runs_delete
BEFORE DELETE ON extraction_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
