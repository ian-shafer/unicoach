-- supporter_llm_costs + supporter_llm_cost: the adult side's owner row on the
-- RFC 106 call log, and the cost view over it. RFC 244, product slice
-- family/03.5/who-pays (brief 0014).
--
-- WHY IT EXISTS NOW, before anything writes to it. A supporter's spend has
-- nowhere to be recorded today: `student_llm_cost` (0041) is a view over four
-- STUDENT-keyed owner tables, so a parent's meter reads a constant zero, the
-- free allowance can never be reached, and the pot a shared Apple ID pays for
-- cannot count what the parent spent. An allowance that cannot be metered is not
-- an allowance. `family/06/the-parent-coach` writes the one row per call; this is
-- the shape it writes through, and the reason that slice PREFERS this one.
--
-- IT ADDS NO WRITER TO ANY EXISTING PATH, so no existing query changes its
-- answer. The table is empty for every account alive today.
--
-- The composition mirrors `convo_requests` and `extraction_runs`: an owner table
-- carries the llm_request_id, and a cost view joins it to the frozen per-call
-- cost on `llm_responses` (0041). One row per attributed call.

CREATE TABLE supporter_llm_costs (
    -- PRIMARY KEY, not merely a foreign key: one attributed call is one row, and
    -- the key is what says a call cannot be charged to two supporters.
    llm_request_id BIGINT      NOT NULL PRIMARY KEY REFERENCES llm_requests(id),
    supporter_id   UUID        NOT NULL REFERENCES supporters(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The meter's read: this supporter's attributed calls. No `deleted_at`
-- predicate, because this table has none -- see the trigger below.
CREATE INDEX supporter_llm_costs_supporter_id_idx ON supporter_llm_costs (supporter_id);

-- A LOG ROW, not an entity. Immutable like `llm_requests` (0038) and
-- `convo_requests`: attribution is a fact about a call that already happened, so
-- there is no correct UPDATE and no soft delete. `prevent_log_update()` (0006)
-- is the shared guard for exactly this, and its message ("log rows are
-- append-only") is the right one here, where the entity guard's "use soft
-- deletes" would be wrong. DELETE is left alone for the same reason
-- `convo_requests` leaves it alone: the row is a child of `llm_requests`, and
-- retention on the call log is what removes it.
CREATE TRIGGER trigger_00_prevent_supporter_llm_costs_update
BEFORE UPDATE ON supporter_llm_costs FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

-- ---------------------------------------------------------------------------
-- supporter_llm_cost -- shaped EXACTLY like student_llm_cost (0041).
--
-- Same columns in the same order with `supporter_id` where `student_id` stands,
-- and the window is on the RESPONSE's `created_at` and not the owner row's, so a
-- period means the same thing on both sides of the house. Same orphan exclusion,
-- inherited from the INNER join: a cost row whose owner row is absent is
-- invisible, RFC 106's semantics unchanged.
--
-- Per-call grain, not pre-aggregated, which is what lets it be windowed -- and
-- what lets `account_llm_cost` union it with the student side without either
-- view being rewritten.
-- ---------------------------------------------------------------------------

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
