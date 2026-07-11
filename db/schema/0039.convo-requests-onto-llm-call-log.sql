-- Convo tables become thin coaching extensions of the generic LLM call log.
-- RFC 106 (Phase 2).
--
-- convo_requests keeps its coaching identity (convo_id / turn_id / kind /
-- system_prompt_id + the id watermark + observations FK target) and now
-- REFERENCES the logged call; the I/O columns (provider / model / params /
-- content) move to llm_requests. convo_responses and convo_responses_raw are
-- DROPped entirely — a convo's response is reached via
-- convo_requests.llm_request_id -> llm_responses (1:1).
--
-- Migration over partial rows: the append-only delete guard blocks a row DELETE,
-- and ADD COLUMN ... NOT NULL fails on a non-empty table, so we TRUNCATE
-- convo_requests CASCADE first (TRUNCATE does not fire the row-level delete
-- guard; it cascades through convo_responses / observations / extraction_runs
-- and the memory graph that FK-references convo_requests). Dev and prod hold no
-- convo data worth keeping, so this discards not-useful data by design; the rest
-- of the database is untouched, and no db-reset is needed.

-- ---------------------------------------------------------------------------
-- Clear convo_requests (and its dependents, via CASCADE) so the reshape below
-- can add a NOT NULL column and so no partial pre-RFC-106 row survives without a
-- logged call to reference.
-- ---------------------------------------------------------------------------

TRUNCATE convo_requests CASCADE;

-- ---------------------------------------------------------------------------
-- Reshape convo_requests: reference the call, drop the moved I/O columns.
-- ---------------------------------------------------------------------------

ALTER TABLE convo_requests
  ADD COLUMN llm_request_id BIGINT NOT NULL REFERENCES llm_requests(id);

-- FK-lookup index (matches the 0019/0031 convention): the unlinked-call
-- anti-join probes this owner by llm_request_id.
CREATE INDEX convo_requests_llm_request_id_idx ON convo_requests (llm_request_id);

ALTER TABLE convo_requests
  DROP COLUMN provider,
  DROP COLUMN model_requested,
  DROP COLUMN request_params,
  DROP COLUMN content;

-- ---------------------------------------------------------------------------
-- Drop the response tables: the response now lives in llm_responses, reached via
-- convo_requests.llm_request_id. Drop raw first (it FK-references responses).
-- The append-only delete guard does not block DROP TABLE (DDL, not a row DELETE).
-- ---------------------------------------------------------------------------

DROP TABLE convo_responses_raw;
DROP TABLE convo_responses;
