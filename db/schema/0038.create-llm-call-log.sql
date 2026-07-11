-- Provider-agnostic LLM call log: the single, domain-free record of every LLM
-- provider call — request envelope, terminal response, and verbatim raw payload.
-- RFC 106.
--
-- Three append-only tables carrying only provider-agnostic facts (no
-- student_id / convo_id / system_prompt_id): attribution and provenance live in
-- the domain rows that reference the call (convo_requests, *_runs). This is the
-- generalization of the RFC-32 convo_requests/_responses/_raw triple, now shared
-- by the four RFC-104 structured-output calls whose tool_use was previously
-- unobservable.
--
-- Shape: llm_requests (1) -> llm_responses (1:1) -> llm_responses_raw (0..1).
-- Every logged call opens an llm_requests row and, before returning, writes
-- exactly one llm_responses row (LlmCallLog write-path discipline); raw is
-- present only when the terminal carried a body.
--
-- Reuses the shared append-only guard functions from 0006 (prevent_log_update /
-- prevent_log_delete) — no new function. Closed enums are TEXT + named CHECK
-- (project convention). PostgreSQL 18.

-- ---------------------------------------------------------------------------
-- llm_requests — append-only log: the full logical request envelope.
--
-- Holds ChatRequest verbatim: provider (ChatProvider.id), the requested model,
-- the system body, the sent message array (content), max_tokens, and the
-- optional tools / tool_choice / params. No domain columns.
-- ---------------------------------------------------------------------------

CREATE TABLE llm_requests (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  provider        TEXT  NOT NULL,   -- ChatProvider.id, verbatim
  model_requested TEXT  NOT NULL,   -- ChatRequest.model
  system          TEXT  NULL,       -- ChatRequest.system (verbatim body; NULL when none)
  content         JSONB NOT NULL,   -- ChatRequest.messages (the sent message array)
  max_tokens      INTEGER NOT NULL, -- ChatRequest.maxTokens
  tools           JSONB NULL,       -- ChatRequest.tools   (NULL when empty)
  tool_choice     JSONB NULL,       -- ChatRequest.toolChoice
  params          JSONB NULL,       -- ChatRequest.params

  -- Vendor allowlist (TEXT + CHECK, project convention). 'log' is the test/stub
  -- provider id; widen this list in a later migration as providers are added.
  CONSTRAINT llm_requests_provider_valid_check CHECK (provider IN ('anthropic','log')),
  CONSTRAINT llm_requests_model_requested_not_empty_check CHECK (length(trim(model_requested)) > 0),
  CONSTRAINT llm_requests_content_is_array_check CHECK (jsonb_typeof(content) = 'array'),
  CONSTRAINT llm_requests_tools_is_array_check CHECK (tools IS NULL OR jsonb_typeof(tools) = 'array'),
  CONSTRAINT llm_requests_tool_choice_is_object_check CHECK (tool_choice IS NULL OR jsonb_typeof(tool_choice) = 'object'),
  CONSTRAINT llm_requests_params_is_object_check CHECK (params IS NULL OR jsonb_typeof(params) = 'object'),
  CONSTRAINT llm_requests_max_tokens_positive_check CHECK (max_tokens > 0)
);

CREATE TRIGGER trigger_00_prevent_llm_requests_update
BEFORE UPDATE ON llm_requests FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_llm_requests_delete
BEFORE DELETE ON llm_requests FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- llm_responses — append-only log, 1:1 with llm_requests.
--
-- The classified terminal. outcome discriminates completed / rejected /
-- transient_failure / cancelled / internal_error. On completed, content /
-- model_resolved / stop_reason are set and reason is null; on every failure
-- arm, reason is set (the classifier's per-arm source) and those three are
-- null. Tokens and provider_request_id are orthogonal to the outcome (a failed
-- or cancelled call may still carry partial usage), so they are plain nullable
-- siblings, not tied to the outcome CHECKs. latency_ms is always recorded.
-- ---------------------------------------------------------------------------

CREATE TABLE llm_responses (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  -- Named so the 1:1 idempotency race (LlmCallLog.writeCancelledIfAbsent) can be
  -- matched precisely by ConstraintViolationException.constraint, rather than
  -- swallowing any unique/CHECK violation.
  request_id BIGINT NOT NULL REFERENCES llm_requests(id) ON DELETE CASCADE,
  CONSTRAINT llm_responses_request_id_key UNIQUE (request_id),

  outcome TEXT NOT NULL,             -- completed | rejected | transient_failure | cancelled | internal_error

  content             JSONB NULL,    -- assistant blocks; non-null iff completed
  model_resolved      TEXT  NULL,    -- non-null iff completed
  stop_reason         TEXT  NULL,    -- verbatim; non-null iff completed
  provider_request_id TEXT  NULL,
  reason              TEXT  NULL,    -- failure classification; null iff completed

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,
  latency_ms         INTEGER NOT NULL,

  CONSTRAINT llm_responses_outcome_valid_check
    CHECK (outcome IN ('completed','rejected','transient_failure','cancelled','internal_error')),
  CONSTRAINT llm_responses_completed_content_check
    CHECK ((content IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_completed_model_check
    CHECK ((model_resolved IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_completed_stop_reason_check
    CHECK ((stop_reason IS NOT NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_reason_presence_check
    CHECK ((reason IS NULL) = (outcome = 'completed')),
  CONSTRAINT llm_responses_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  ),
  CONSTRAINT llm_responses_latency_nonneg_check CHECK (latency_ms >= 0)
);

CREATE TRIGGER trigger_00_prevent_llm_responses_update
BEFORE UPDATE ON llm_responses FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_llm_responses_delete
BEFORE DELETE ON llm_responses FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- llm_responses_raw — append-only log, isolated verbatim payload, 0..1 per
-- response. Keyed 1:1 to its response by making the FK the PK. Absent when a
-- failure terminal carried no body.
-- ---------------------------------------------------------------------------

CREATE TABLE llm_responses_raw (
  response_id BIGINT NOT NULL PRIMARY KEY REFERENCES llm_responses(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  payload     JSONB NOT NULL
);

CREATE TRIGGER trigger_00_prevent_llm_responses_raw_update
BEFORE UPDATE ON llm_responses_raw FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_llm_responses_raw_delete
BEFORE DELETE ON llm_responses_raw FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
