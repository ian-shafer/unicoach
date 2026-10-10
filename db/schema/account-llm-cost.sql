-- account_llm_cost: the cost ledger, keyed by ACCOUNT. RFC 244, product slice
-- family/03.5/who-pays (brief 0014).
--
-- WHY. The entitlement subject is an ACCOUNT now, not a student (RFC 244: every
-- account pays for itself), so the meter the budget gate reads needs a key of
-- `users.id`. One human may hold BOTH a student row and a supporter row --
-- `SupporterService.ensure` is deliberately silent about that -- and that one
-- account holds ONE subscription, so its spend on both sides is one total.
--
-- BUILT FROM THE TWO OWNER-SIDE VIEWS, so NEITHER IS REWRITTEN. `student_llm_cost`
-- (0041) keeps its exact definition and its exact answer, and
-- `supporter_llm_cost` keeps its own; this view only re-keys them onto the
-- account and unions the two. That is the property the RFC 244 regression rests
-- on: for every account alive today the second arm is EMPTY (nothing writes
-- `supporter_llm_costs` yet), so every existing student's meter reads exactly
-- the number it read before -- a claim the tests make by reading both.
--
-- UNION ALL, not UNION: the grain is per call and two calls may legitimately
-- agree on every projected column (same instant, same frozen cost, same token
-- counts). UNION would silently collapse them and under-count the spend.
--
-- No `deleted_at` predicate on `students` or `supporters`, matching
-- `student_llm_cost`'s own posture (0041 joins `convos` with no soft-delete
-- filter): a closed account's past spend is still spend, and a meter that
-- forgets it would reset a lifetime allowance by closing a profile. Both joins
-- are INNER because both owner columns are real foreign keys -- an owner row
-- with no account is not a state this view may invent a key for.
--
-- Same columns as the two views it unions, so a reader that windows or sums one
-- of them windows or sums this identically.

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
