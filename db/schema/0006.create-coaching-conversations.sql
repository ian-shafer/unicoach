-- Coaching conversations: the immutable source of truth for student<->LLM
-- coaching exchanges. RFC 32.
--
-- Shape: entity-that-owns-logs.
--   convos                — mutable entity owned by a student (1:many), UUIDv7 PK.
--   convo_requests        — append-only log: one row per turn sent to the model.
--   convo_responses       — append-only log: one row per model reply (1:1 request).
--   convo_responses_raw   — append-only log: verbatim provider payload (1:1 response).
--
-- The Anthropic Messages API is stateless; the database is the coach's only
-- durable memory. Capture completely and verbatim at write time.

-- ---------------------------------------------------------------------------
-- Log-guard functions (new shared functions).
--
-- 0000.shared-functions.sql is already applied and append-only; bin/db-migrate
-- skips files whose version_id is already recorded, so editing 0000 would not
-- re-run on existing databases. These new append-only guards are therefore
-- defined here (idempotent CREATE OR REPLACE). They are distinct from the
-- entity prevent_physical_delete(), whose message ("use soft deletes") is wrong
-- for an append-only log.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_log_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows are append-only and cannot be updated.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_log_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Log rows cannot be deleted; prune by partition/retention.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- convos — mutable entity
-- ---------------------------------------------------------------------------

CREATE TABLE convos (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),

  -- Timestamps (4-timestamp advanced pattern; reuses shared functions)
  created_at     TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at     TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at     TIMESTAMPTZ NULL,

  -- Ownership. CASCADE wires the future physical-erasure path; it stays inert
  -- while prevent_physical_delete blocks all physical deletes (see Deletion &
  -- cascade).
  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

  -- Mutable domain field (the reason this is an entity, not a log). Set at
  -- creation — typically derived from the first user turn — and editable after.
  name TEXT NOT NULL,

  CONSTRAINT convos_name_length_check    CHECK (length(name) <= 255),
  CONSTRAINT convos_name_not_empty_check CHECK (length(trim(name)) > 0),
  CONSTRAINT convos_name_trimmed_check   CHECK (name = trim(name))
);

-- Replay/list a student's active conversations.
CREATE INDEX convos_student_id_idx ON convos (student_id) WHERE deleted_at IS NULL;

-- BEFORE triggers fire in alphabetical name order (00 / 00a / 03 convention).
CREATE TRIGGER trigger_00_prevent_convos_physical_delete
BEFORE DELETE ON convos
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_convos_immutable_updates
BEFORE UPDATE ON convos
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_03_enforce_convos_updated_at
BEFORE UPDATE ON convos
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

-- No enforce_versioning, no trim trigger, no *_versions log trigger
-- (versioning disabled — see D-3).

-- ---------------------------------------------------------------------------
-- convo_requests — append-only log
--
-- One row = one turn sent to the model: the new user input plus the request
-- envelope. It deliberately does NOT store the replayed prior history (the
-- stateless API resends it each turn; re-storing it per row would be O(n^2)).
-- ---------------------------------------------------------------------------

CREATE TABLE convo_requests (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- internal, monotonic
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  convo_id   UUID NOT NULL REFERENCES convos(id) ON DELETE CASCADE,

  -- Provenance pinned on every exchange.
  provider              TEXT  NOT NULL,   -- LLM vendor; allowlisted below (per-turn, see D-7)
  model_requested       TEXT  NOT NULL,   -- model id requested within the provider (e.g. claude-opus-4-8)
  system_prompt_version TEXT  NOT NULL,   -- verbatim version pin (see D-2; no FK yet)
  request_params        JSONB NULL,       -- vendor params (temperature, max_tokens, ...); interpreted via provider

  -- The new user input for this turn. Opaque structured JSONB; the DB does not
  -- constrain its internal shape (see Content representation).
  content JSONB NOT NULL,

  -- Vendor allowlist (TEXT + CHECK, project convention; NOT a native pg enum).
  -- Extend the list in a later migration as providers are added.
  CONSTRAINT convo_requests_provider_valid_check
    CHECK (provider IN ('anthropic')),
  CONSTRAINT convo_requests_model_requested_length_check
    CHECK (length(model_requested) <= 255),
  CONSTRAINT convo_requests_model_requested_not_empty_check
    CHECK (length(trim(model_requested)) > 0),
  CONSTRAINT convo_requests_model_requested_trimmed_check
    CHECK (model_requested = trim(model_requested)),
  CONSTRAINT convo_requests_system_prompt_version_length_check
    CHECK (length(system_prompt_version) <= 255),
  CONSTRAINT convo_requests_system_prompt_version_not_empty_check
    CHECK (length(trim(system_prompt_version)) > 0),
  CONSTRAINT convo_requests_system_prompt_version_trimmed_check
    CHECK (system_prompt_version = trim(system_prompt_version)),
  CONSTRAINT convo_requests_request_params_is_object_check
    CHECK (request_params IS NULL OR jsonb_typeof(request_params) = 'object'),
  -- Bounded user input. 1 MiB; revisit if larger inputs become legitimate.
  CONSTRAINT convo_requests_content_size_check
    CHECK (octet_length(content::text) <= 1048576)
);

CREATE INDEX convo_requests_convo_id_created_at_idx
  ON convo_requests (convo_id, created_at);

CREATE TRIGGER trigger_00_prevent_convo_requests_update
BEFORE UPDATE ON convo_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_convo_requests_delete
BEFORE DELETE ON convo_requests
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- convo_responses — append-only log, 1:1 with request
--
-- The model's reply plus the response envelope. request_id is UNIQUE, enforcing
-- the 1:1 request<->response relationship at the DB level. A failed request with
-- no usable reply is recorded as a row with stop_reason = 'error' and
-- content = NULL — the attempt is never silently dropped.
-- ---------------------------------------------------------------------------

CREATE TABLE convo_responses (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  request_id BIGINT NOT NULL UNIQUE REFERENCES convo_requests(id) ON DELETE CASCADE,
  convo_id   UUID   NOT NULL REFERENCES convos(id) ON DELETE CASCADE,  -- denormalized; see note

  content        JSONB NULL,    -- assistant output; opaque JSONB; NULL only on error
  model_resolved TEXT  NULL,    -- exact model that actually ran; NULL only on error
  stop_reason    TEXT  NOT NULL, -- end_turn | max_tokens | stop_sequence | tool_use | error | ...

  input_tokens       INTEGER NULL,
  output_tokens      INTEGER NULL,
  cache_read_tokens  INTEGER NULL,
  cache_write_tokens INTEGER NULL,

  provider_request_id TEXT    NULL,  -- provider's request/response id for cross-ref
  latency_ms          INTEGER NULL,

  CONSTRAINT convo_responses_stop_reason_length_check
    CHECK (length(stop_reason) <= 64),
  CONSTRAINT convo_responses_stop_reason_not_empty_check
    CHECK (length(trim(stop_reason)) > 0),
  CONSTRAINT convo_responses_stop_reason_trimmed_check
    CHECK (stop_reason = trim(stop_reason)),
  CONSTRAINT convo_responses_model_resolved_length_check
    CHECK (model_resolved IS NULL OR length(model_resolved) <= 255),
  CONSTRAINT convo_responses_model_resolved_not_empty_check
    CHECK (model_resolved IS NULL OR length(trim(model_resolved)) > 0),
  CONSTRAINT convo_responses_model_resolved_trimmed_check
    CHECK (model_resolved IS NULL OR model_resolved = trim(model_resolved)),
  -- Content/model are present on success, NULL only when the turn errored.
  CONSTRAINT convo_responses_content_presence_check
    CHECK (content IS NOT NULL OR stop_reason = 'error'),
  CONSTRAINT convo_responses_model_presence_check
    CHECK (model_resolved IS NOT NULL OR stop_reason = 'error'),
  CONSTRAINT convo_responses_provider_request_id_length_check
    CHECK (provider_request_id IS NULL OR length(provider_request_id) <= 255),
  CONSTRAINT convo_responses_provider_request_id_not_empty_check
    CHECK (provider_request_id IS NULL OR length(trim(provider_request_id)) > 0),
  CONSTRAINT convo_responses_provider_request_id_trimmed_check
    CHECK (provider_request_id IS NULL OR provider_request_id = trim(provider_request_id)),
  CONSTRAINT convo_responses_tokens_nonneg_check CHECK (
    (input_tokens       IS NULL OR input_tokens       >= 0) AND
    (output_tokens      IS NULL OR output_tokens      >= 0) AND
    (cache_read_tokens  IS NULL OR cache_read_tokens  >= 0) AND
    (cache_write_tokens IS NULL OR cache_write_tokens >= 0)
  ),
  CONSTRAINT convo_responses_latency_nonneg_check
    CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX convo_responses_convo_id_created_at_idx
  ON convo_responses (convo_id, created_at);

CREATE TRIGGER trigger_00_prevent_convo_responses_update
BEFORE UPDATE ON convo_responses
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_convo_responses_delete
BEFORE DELETE ON convo_responses
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

-- ---------------------------------------------------------------------------
-- convo_responses_raw — append-only log, isolated verbatim payload
--
-- The verbatim provider response, kept because it is irreplaceable and so the
-- fidelity backup can be archived or dropped later without rewriting the hot
-- convo_responses rows. Keyed 1:1 to its response by making the FK the PK
-- (guarantees at most one raw row per response).
-- ---------------------------------------------------------------------------

CREATE TABLE convo_responses_raw (
  response_id BIGINT NOT NULL PRIMARY KEY REFERENCES convo_responses(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  payload     JSONB NOT NULL  -- exact provider response body, verbatim
);

CREATE TRIGGER trigger_00_prevent_convo_responses_raw_update
BEFORE UPDATE ON convo_responses_raw
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_convo_responses_raw_delete
BEFORE DELETE ON convo_responses_raw
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
