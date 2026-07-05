-- fit-lens: a per-student between-sessions reflection that reaches into the
-- college dataset and proposes one real school the student has not mentioned,
-- with a rationale grounded in what they said. A sibling of synthesis (RFC 93),
-- not a lens inside it. RFC 98.
--
-- Two tables:
--   fit_suggestions — mutable entity: the coach's proposed school for a student,
--                     modeled on commitments (four-timestamp split, no
--                     versioning, no deleted_at). Carries the deterministic
--                     novelty backstop UNIQUE(student_id, college_id).
--   fit_lens_runs   — append-only log: the per-student token ledger (summing
--                     the pass's two billed calls), provenance (two prompt pins,
--                     provider/model), and the freshness marker.
--
-- Both reuse the shared guard functions from prior migrations
-- (prevent_physical_delete, prevent_immutable_updates,
-- prevent_physical_timestamp_update, update_timestamp, prevent_log_update,
-- prevent_log_delete) with no new function. Closed enums are TEXT + named CHECK
-- (project convention). PostgreSQL 18; uuidv7() is built-in.

-- ---------------------------------------------------------------------------
-- fit_suggestions — mutable entity
--
-- The coach's proposed school for a student. It resolves from 'open' (proposed,
-- not yet raised) to 'surfaced' (raised in the next-session opener). Modeled
-- exactly on commitments: the four-timestamp split, no versioning, no
-- deleted_at. The UNIQUE(student_id, college_id) constraint is the novelty
-- backstop: a college is proposed to a student at most once, ever.
-- ---------------------------------------------------------------------------

CREATE TABLE fit_suggestions (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT, -- colleges are never physically deleted (RFC 67/0023)

  status    TEXT NOT NULL DEFAULT 'open',   -- open | surfaced
  rationale TEXT NOT NULL,                   -- the coach's pitch, grounded in real match numbers

  surfaced_at          TIMESTAMPTZ NULL,
  surfaced_in_convo_id UUID        NULL REFERENCES convos(id) ON DELETE RESTRICT,

  CONSTRAINT fit_suggestions_status_check           CHECK (status IN ('open','surfaced')),
  CONSTRAINT fit_suggestions_rationale_length_check CHECK (length(rationale) <= 2048),
  CONSTRAINT fit_suggestions_rationale_not_empty_check CHECK (length(trim(rationale)) > 0),
  -- surfaced iff both surfacing columns set; an 'open' row has both NULL.
  CONSTRAINT fit_suggestions_surfaced_consistency_check CHECK (
    (status = 'surfaced') = (surfaced_at IS NOT NULL AND surfaced_in_convo_id IS NOT NULL)
  ),
  -- The novelty backstop: a college is proposed to a student at most once, ever.
  CONSTRAINT fit_suggestions_student_college_unique UNIQUE (student_id, college_id)
);

-- The opener read (delivery): open suggestions for a student.
CREATE INDEX fit_suggestions_student_open_idx ON fit_suggestions (student_id) WHERE status = 'open';
-- Per-student novelty recheck + admin filtering.
CREATE INDEX fit_suggestions_student_idx      ON fit_suggestions (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_fit_suggestions_physical_delete
BEFORE DELETE ON fit_suggestions FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();
CREATE TRIGGER trigger_00a_prevent_fit_suggestions_immutable_updates
BEFORE UPDATE ON fit_suggestions FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON fit_suggestions FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();
CREATE TRIGGER trigger_03_enforce_fit_suggestions_updated_at
BEFORE UPDATE ON fit_suggestions FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

-- ---------------------------------------------------------------------------
-- fit_lens_runs — append-only log (token ledger + provenance + freshness marker)
--
-- One row per completed fit-lens pass over a student — 'applied' or 'failed'.
-- Because a pass makes two billed LLM calls, its four token columns hold the SUM
-- of both calls (every token on a completed pass recorded, failed passes
-- included). It mirrors synthesis_runs and serves three jobs: the student's
-- fit-lens freshness marker (MAX(created_at) WHERE outcome = 'applied'); the
-- provenance of the pass (two prompt pins, provider/model); and the per-student
-- token ledger.
-- ---------------------------------------------------------------------------

CREATE TABLE fit_lens_runs (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- 'applied' completed a full pass and advances the freshness marker (with or
  -- without a suggestion written); 'failed' billed tokens for unusable output.
  outcome TEXT NOT NULL,

  query_system_prompt_id  UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  reason_system_prompt_id UUID NOT NULL REFERENCES system_prompts(id) ON DELETE RESTRICT,
  provider       TEXT NOT NULL,
  model_resolved TEXT NULL,

  suggestions_written INTEGER NOT NULL DEFAULT 0,  -- 0 (no novel fit) or 1
  -- size of the retrieved set call #2 saw: 0 for a completed retrieve that
  -- matched nothing; NULL only when the retrieve call never ran (a Failed pass
  -- that died at LLM call #1, before any search).
  matches_considered  INTEGER NULL,

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  CONSTRAINT fit_lens_runs_outcome_check  CHECK (outcome IN ('applied','failed')),
  CONSTRAINT fit_lens_runs_provider_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT fit_lens_runs_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT fit_lens_runs_suggestions_bounds_check CHECK (suggestions_written BETWEEN 0 AND 1),
  CONSTRAINT fit_lens_runs_failed_counts_check
    CHECK (outcome <> 'failed' OR suggestions_written = 0),
  CONSTRAINT fit_lens_runs_matches_nonneg_check
    CHECK (matches_considered IS NULL OR matches_considered >= 0),
  CONSTRAINT fit_lens_runs_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  )
);

-- Freshness marker: latest applied run per student.
CREATE INDEX fit_lens_runs_student_applied_idx
  ON fit_lens_runs (student_id, created_at) WHERE outcome = 'applied';
-- Per-student token-accounting scan + consecutive-failure count.
CREATE INDEX fit_lens_runs_student_idx ON fit_lens_runs (student_id, created_at);

CREATE TRIGGER trigger_00_prevent_fit_lens_runs_update
BEFORE UPDATE ON fit_lens_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_fit_lens_runs_delete
BEFORE DELETE ON fit_lens_runs FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
