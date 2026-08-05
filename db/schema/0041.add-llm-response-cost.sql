-- Per-call frozen dollar cost on the RFC 106 call log, plus a per-call
-- attribution spine that replaces the lifetime-aggregated
-- student_llm_token_usage view. RFC 108.
--
-- Two additive nullable columns (no table rewrite, no delete-guard trip, so no
-- TRUNCATE), an index correction, and a view swap. Pre-existing rows read NULL
-- and stay permanently uncosted (surfaced as uncostedCalls) — backfilling them
-- at a price they were not made under would be worse than a countable gap.

-- ---------------------------------------------------------------------------
-- llm_responses — frozen per-call cost.
--
-- cost_nanodollars is the dollar cost of THIS call at the price in effect when
-- the call was written (nano-dollars = 1e-9 USD; integer so a running meter
-- cannot drift). cost_is_estimated qualifies it: true when priced at
-- llmPricing.default (the resolved model was absent from the price book). The
-- flag is present exactly when there is a cost to qualify — same idiom as
-- 0038's llm_responses_completed_model_check.
-- ---------------------------------------------------------------------------

ALTER TABLE llm_responses
  ADD COLUMN cost_nanodollars BIGINT NULL;   -- frozen $ cost of THIS call, price-at-time-of-call
ALTER TABLE llm_responses
  ADD COLUMN cost_is_estimated BOOLEAN NULL; -- true when priced at llmPricing.default (model not in the book)
ALTER TABLE llm_responses
  ADD CONSTRAINT llm_responses_cost_nonneg_check
    CHECK (cost_nanodollars IS NULL OR cost_nanodollars >= 0);
ALTER TABLE llm_responses
  ADD CONSTRAINT llm_responses_cost_estimated_check
    CHECK ((cost_is_estimated IS NOT NULL) = (cost_nanodollars IS NOT NULL));

-- ---------------------------------------------------------------------------
-- convos_student_id_idx — plain, not partial.
--
-- The spine's chat branch selects convos by student_id with NO deleted_at
-- predicate, so 0006's PARTIAL convos_student_id_idx (WHERE deleted_at IS NULL)
-- cannot serve it and every meter read would seq scan convos. A plain index
-- serves the spine and the existing deleted_at IS NULL reads both, so it
-- replaces rather than doubles.
-- ---------------------------------------------------------------------------

DROP INDEX convos_student_id_idx;
CREATE INDEX convos_student_id_idx ON convos (student_id);

-- ---------------------------------------------------------------------------
-- student_llm_cost — per-call attribution spine (replaces student_llm_token_usage).
--
-- One row per attributed call, the four-owner union in exactly one place. Not
-- pre-aggregated, so it windows by created_at. Carries the token columns so a
-- future token-totals reader is a GROUP BY away. RFC 106's attribution
-- semantics are unchanged (same union, same soft-deleted inclusion via the
-- deleted_at-less convos join, same orphan exclusion); the spine only lowers
-- the grain to per-call and adds created_at + the two cost columns.
-- ---------------------------------------------------------------------------

DROP VIEW student_llm_token_usage;

CREATE VIEW student_llm_cost AS
WITH per_call AS (
  SELECT c.student_id, cr.llm_request_id
  FROM convo_requests cr JOIN convos c ON c.id = cr.convo_id
  UNION ALL
  SELECT er.student_id, er.llm_request_id FROM extraction_runs er
  UNION ALL
  SELECT sr.student_id, sr.llm_request_id FROM synthesis_runs sr
  UNION ALL
  SELECT flr.student_id, flr.query_llm_request_id AS llm_request_id FROM fit_lens_runs flr
  UNION ALL
  SELECT flr.student_id, flr.reason_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr WHERE flr.reason_llm_request_id IS NOT NULL
)
SELECT
  pc.student_id,
  resp.created_at,
  resp.cost_nanodollars,
  resp.cost_is_estimated,
  resp.input_tokens,
  resp.output_tokens,
  resp.cache_read_tokens,
  resp.cache_write_tokens
FROM per_call pc
JOIN llm_responses resp ON resp.request_id = pc.llm_request_id;
