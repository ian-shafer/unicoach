-- The six keys that lead from an owner row to an LLM call are typed
-- owned.llm_request_id (RFC 263, slice deletion/01/the-classification, brief
-- 0002 D3).
--
-- The LLM log (llm_requests -> llm_responses -> llm_responses_raw) holds a
-- person's words but has no key to any account: the owner rows point AT it. An
-- erasure walk that only went child-ward from the account would never get
-- there. Typing these keys owned.* is what tells the walk to follow them
-- forward; ErasureClassificationTest holds every such key to it.
--
-- PostgreSQL refuses ALTER COLUMN TYPE on a column a view reads, and the three
-- cost views read every one of these. So this is ONE migration across five
-- tables: drop the views (account_llm_cost first -- it selects from the other
-- two), retype, and recreate each from its current definition UNCHANGED:
-- student_llm_cost from convos-party.sql, supporter_llm_cost from
-- supporter-llm-cost.sql, account_llm_cost from account-llm-cost.sql. The
-- column lists and row sets are what they were; only the key columns' type
-- name differs, and a domain over BIGINT joins with BIGINT's own operator.

DROP VIEW account_llm_cost;
DROP VIEW student_llm_cost;
DROP VIEW supporter_llm_cost;

ALTER TABLE convo_requests
    ALTER COLUMN llm_request_id TYPE owned.llm_request_id;

ALTER TABLE extraction_runs
    ALTER COLUMN llm_request_id TYPE owned.llm_request_id;

ALTER TABLE synthesis_runs
    ALTER COLUMN llm_request_id TYPE owned.llm_request_id;

ALTER TABLE fit_lens_runs
    ALTER COLUMN query_llm_request_id  TYPE owned.llm_request_id,
    ALTER COLUMN reason_llm_request_id TYPE owned.llm_request_id;

ALTER TABLE supporter_llm_costs
    ALTER COLUMN llm_request_id TYPE owned.llm_request_id;

-- convos-party.sql's definition, verbatim.
CREATE VIEW student_llm_cost AS
WITH per_call AS (
  SELECT c.student_id, cr.llm_request_id
  FROM convo_requests cr JOIN convos c ON c.id = cr.convo_id
  WHERE c.party = 'student'
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

-- supporter-llm-cost.sql's definition, verbatim.
CREATE VIEW supporter_llm_cost AS
SELECT
  slc.supporter_id,
  resp.created_at,
  resp.cost_nanodollars,
  resp.cost_is_estimated,
  resp.input_tokens,
  resp.output_tokens,
  resp.cache_read_tokens,
  resp.cache_write_tokens
FROM supporter_llm_costs slc
JOIN llm_responses resp ON resp.request_id = slc.llm_request_id;

-- account-llm-cost.sql's definition, verbatim.
CREATE VIEW account_llm_cost AS
SELECT
  st.user_id,
  c.created_at,
  c.cost_nanodollars,
  c.cost_is_estimated,
  c.input_tokens,
  c.output_tokens,
  c.cache_read_tokens,
  c.cache_write_tokens
FROM student_llm_cost c
JOIN students st ON st.id = c.student_id
UNION ALL
SELECT
  sp.user_id,
  c.created_at,
  c.cost_nanodollars,
  c.cost_is_estimated,
  c.input_tokens,
  c.output_tokens,
  c.cache_read_tokens,
  c.cache_write_tokens
FROM supporter_llm_cost c
JOIN supporters sp ON sp.id = c.supporter_id;
