-- The three run tables shrink to outcome/watermark records referencing the
-- generic LLM call log, and a read-only per-student token-ledger view unions
-- their four call owners. RFC 106 (Phase 3).
--
-- Each *_runs table drops the columns now owned by llm_requests/llm_responses
-- (provider, model_resolved, and the four token columns) and gains an
-- llm_request_id reference to the logged call(s): extraction_runs and
-- synthesis_runs one NOT NULL id each; fit_lens_runs a NOT NULL
-- query_llm_request_id and a NULLABLE reason_llm_request_id (a
-- Rejected/TransientFailure query call bails before the reason call). Each new id
-- column gets a btree index (the unlinked-call anti-join probes each owner by its
-- llm_request_id; 0019/0031 FK-lookup-index convention).
--
-- A run's outcome (applied/failed) is the DOMAIN outcome (did we parse and
-- apply); llm_responses.outcome is the TRANSPORT outcome (did the provider
-- respond). They are independent — a completed call can back a failed run — so
-- the runs keep their outcome/failure columns.
--
-- Migration over partial rows: ADD COLUMN ... NOT NULL fails on a non-empty
-- table and the delete guard blocks a row DELETE, so each table is TRUNCATEd
-- first. Dev and prod hold no run data worth keeping.

-- ---------------------------------------------------------------------------
-- extraction_runs
-- ---------------------------------------------------------------------------

TRUNCATE extraction_runs CASCADE;

ALTER TABLE extraction_runs
  ADD COLUMN llm_request_id BIGINT NOT NULL REFERENCES llm_requests(id);
CREATE INDEX extraction_runs_llm_request_id_idx ON extraction_runs (llm_request_id);

ALTER TABLE extraction_runs
  DROP COLUMN provider,
  DROP COLUMN model_resolved,
  DROP COLUMN input_tokens,
  DROP COLUMN output_tokens,
  DROP COLUMN cache_read_tokens,
  DROP COLUMN cache_write_tokens;

-- ---------------------------------------------------------------------------
-- synthesis_runs
-- ---------------------------------------------------------------------------

TRUNCATE synthesis_runs CASCADE;

ALTER TABLE synthesis_runs
  ADD COLUMN llm_request_id BIGINT NOT NULL REFERENCES llm_requests(id);
CREATE INDEX synthesis_runs_llm_request_id_idx ON synthesis_runs (llm_request_id);

ALTER TABLE synthesis_runs
  DROP COLUMN provider,
  DROP COLUMN model_resolved,
  DROP COLUMN input_tokens,
  DROP COLUMN output_tokens,
  DROP COLUMN cache_read_tokens,
  DROP COLUMN cache_write_tokens;

-- ---------------------------------------------------------------------------
-- fit_lens_runs — two calls per pass (query then reason). Every write path always
-- has a query call (the query call precedes any run write), so
-- query_llm_request_id is NOT NULL; only reason_llm_request_id is NULLABLE — a
-- Rejected/TransientFailure query call or a zero-match retrieve bails before the
-- reason call, leaving it NULL. Both are indexed (the anti-join probes every
-- owner id).
-- ---------------------------------------------------------------------------

TRUNCATE fit_lens_runs CASCADE;

ALTER TABLE fit_lens_runs
  ADD COLUMN query_llm_request_id  BIGINT NOT NULL REFERENCES llm_requests(id),
  ADD COLUMN reason_llm_request_id BIGINT NULL REFERENCES llm_requests(id);
CREATE INDEX fit_lens_runs_query_llm_request_id_idx  ON fit_lens_runs (query_llm_request_id);
CREATE INDEX fit_lens_runs_reason_llm_request_id_idx ON fit_lens_runs (reason_llm_request_id);

ALTER TABLE fit_lens_runs
  DROP COLUMN provider,
  DROP COLUMN model_resolved,
  DROP COLUMN input_tokens,
  DROP COLUMN output_tokens,
  DROP COLUMN cache_read_tokens,
  DROP COLUMN cache_write_tokens;

-- ---------------------------------------------------------------------------
-- student_llm_token_usage — read-only per-student token ledger.
--
-- llm_responses is now the single home of token spend. This view unions the four
-- per-call owners, each joined FROM the domain owner TO llm_responses (via the
-- UNIQUE request_id), yielding per-student totals from one place:
--   * convo_requests  -> convos.student_id  (chat)
--   * extraction_runs -> student_id
--   * synthesis_runs  -> student_id
--   * fit_lens_runs   -> student_id (its two ids, unioned)
--
-- Because the join originates at the domain owners, a call no owner references (a
-- crash-window orphan) contributes to no student and is absent from the view —
-- the ledger is exact for attributed spend and undercounts only by the orphans.
--
-- The convo_requests -> convos join deliberately omits a deleted_at IS NULL
-- predicate (unlike user-facing convo reads): a first-turn-abandoned convo is
-- soft-deleted but its partial spend was genuinely billed, so it is summed into
-- the student's totals rather than pushed into a blind spot.
-- ---------------------------------------------------------------------------

CREATE VIEW student_llm_token_usage AS
WITH per_call AS (
  -- Chat: convo_requests -> convos.student_id
  SELECT c.student_id, cr.llm_request_id
  FROM convo_requests cr
  JOIN convos c ON c.id = cr.convo_id

  UNION ALL

  -- Extraction
  SELECT er.student_id, er.llm_request_id
  FROM extraction_runs er

  UNION ALL

  -- Synthesis
  SELECT sr.student_id, sr.llm_request_id
  FROM synthesis_runs sr

  UNION ALL

  -- Fit-lens query call (query_llm_request_id is NOT NULL on every row)
  SELECT flr.student_id, flr.query_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr

  UNION ALL

  -- Fit-lens reason call
  SELECT flr.student_id, flr.reason_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr
  WHERE flr.reason_llm_request_id IS NOT NULL
)
SELECT
  pc.student_id,
  COALESCE(SUM(resp.input_tokens), 0)       AS input_tokens,
  COALESCE(SUM(resp.output_tokens), 0)      AS output_tokens,
  COALESCE(SUM(resp.cache_read_tokens), 0)  AS cache_read_tokens,
  COALESCE(SUM(resp.cache_write_tokens), 0) AS cache_write_tokens
FROM per_call pc
JOIN llm_responses resp ON resp.request_id = pc.llm_request_id
GROUP BY pc.student_id;
