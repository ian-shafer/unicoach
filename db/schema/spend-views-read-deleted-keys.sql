-- The three spend views read each owner's key as the live-or-deleted pair (RFC
-- 264, slice deletion/02/the-deleted-tables, brief 0002 D9).
--
-- WHY. Erasure moves an owner row's key from the live column to its sibling
-- (deleted-key-*.sql). A view that read only the live column would drop that
-- row's spend the moment the key moved. D9 makes the deleted id the former id,
-- so COALESCE(live, deleted) reports an account's spend under the same id
-- before and after erasure. Exactly one of each pair is set (the pair CHECKs),
-- so the COALESCE never picks between two ids.
--
-- account_llm_cost still joins students.id and supporters.id: D1 keeps those
-- rows, so the join survives erasure; only the account key on them moves.
--
-- Each view is recreated from classify-owned-llm-request-keys.sql's definition
-- with only the COALESCE added: output columns, names and types are unchanged,
-- so AccountLlmCostDao is untouched. Dropped in dependency order
-- (account_llm_cost selects from the other two).

DROP VIEW account_llm_cost;
DROP VIEW student_llm_cost;
DROP VIEW supporter_llm_cost;

CREATE VIEW student_llm_cost AS
WITH per_call AS (
  SELECT COALESCE(c.student_id, c.deleted_student_id) AS student_id, cr.llm_request_id
  FROM convo_requests cr JOIN convos c ON c.id = cr.convo_id
  WHERE c.party = 'student'
  UNION ALL
  SELECT COALESCE(er.student_id, er.deleted_student_id), er.llm_request_id FROM extraction_runs er
  UNION ALL
  SELECT COALESCE(sr.student_id, sr.deleted_student_id), sr.llm_request_id FROM synthesis_runs sr
  UNION ALL
  SELECT COALESCE(flr.student_id, flr.deleted_student_id), flr.query_llm_request_id AS llm_request_id
  FROM fit_lens_runs flr
  UNION ALL
  SELECT COALESCE(flr.student_id, flr.deleted_student_id), flr.reason_llm_request_id AS llm_request_id
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

CREATE VIEW supporter_llm_cost AS
SELECT
  COALESCE(slc.supporter_id, slc.deleted_supporter_id) AS supporter_id,
  resp.created_at,
  resp.cost_nanodollars,
  resp.cost_is_estimated,
  resp.input_tokens,
  resp.output_tokens,
  resp.cache_read_tokens,
  resp.cache_write_tokens
FROM supporter_llm_costs slc
JOIN llm_responses resp ON resp.request_id = slc.llm_request_id;

CREATE VIEW account_llm_cost AS
SELECT
  COALESCE(st.user_id, st.deleted_user_id) AS user_id,
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
  COALESCE(sp.user_id, sp.deleted_user_id) AS user_id,
  c.created_at,
  c.cost_nanodollars,
  c.cost_is_estimated,
  c.input_tokens,
  c.output_tokens,
  c.cache_read_tokens,
  c.cache_write_tokens
FROM supporter_llm_cost c
JOIN supporters sp ON sp.id = c.supporter_id;
