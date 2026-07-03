-- Add convo_requests.turn_id: the logical turn a request row belongs to. RFC 94
-- (chat tool-use loop, F1 refinement).
--
-- All rows of one tool-use excursion — the kind='user' opener plus each
-- kind='tool_result' continuation — share one turn_id, minted once per user turn
-- by the loop. A plain no-tool turn is a singleton. turn_id is the explicit turn
-- boundary the visible-exchange projection and the extraction window group on, so
-- a positional row cap can never split an excursion (the silent per-turn
-- extraction loss this refinement closes).
--
-- turn_id lives in its own namespace (a dedicated sequence), unrelated to id — it
-- is a grouping key, never compared to a convo_requests.id. It is added to
-- convo_requests only, not convo_responses: a response is 1:1 with its request
-- (request_id NOT NULL UNIQUE), so a response's turn is a single join away.
--
-- Backfill mechanism is dictated by the append-only guard
-- (trigger_00_prevent_convo_requests_update raises on every row UPDATE): a per-row
-- UPDATE is blocked, but ADD COLUMN ... GENERATED ALWAYS AS (id) STORED computes
-- the value during the table rewrite (no row-level UPDATE fires the guard).
-- DROP EXPRESSION then makes the column writable while keeping the stored values,
-- so the app can stamp a shared turn_id on an excursion's continuation rows. Each
-- historical row is a complete turn, so turn_id = id reads correctly. Both
-- behaviors were verified against PostgreSQL 18.
--
-- No index: the projection and the extraction window read a convo's turns via
-- ConvosDao.listTurns and group by turn_id in application code, so turn_id is a
-- per-row grouping field, not a SQL lookup key.
--
-- Additive and safe to roll back with the code: legacy rows have turn_id = id;
-- any excursion rows written pre-revert keep their shared turn_id, harmless once
-- the loop that reads it is gone.

CREATE SEQUENCE convo_turn_id_seq;

-- Backfill every existing row as its own singleton turn (turn_id = id) via a
-- STORED generated column: this is a table rewrite, so the append-only update
-- guard never fires (no row-level UPDATE).
ALTER TABLE convo_requests
  ADD COLUMN turn_id BIGINT GENERATED ALWAYS AS (id) STORED;

-- Convert to a plain writable column, preserving the backfilled values, then
-- require it: every insert MUST carry an explicit turn_id (the loop mints one
-- per logical turn), so a forgotten value fails NOT NULL loudly instead of
-- silently minting a fresh id that would split an excursion.
ALTER TABLE convo_requests ALTER COLUMN turn_id DROP EXPRESSION;
ALTER TABLE convo_requests ALTER COLUMN turn_id SET NOT NULL;

-- Seed the sequence past every backfilled turn_id (= MAX(id)) so app-minted
-- turn_ids never collide with the legacy turn_id = id range. On an empty table
-- (no legacy rows — the fresh-DB migration case) there is nothing to seed past,
-- so leave the sequence uncalled at its minimum: setval(seq, 1, is_called=false)
-- makes the first nextval() return 1. A bare setval(seq, MAX(id)) would fail here
-- because MAX(id) is NULL → COALESCE 0 is below the sequence minimum of 1.
SELECT setval(
  'convo_turn_id_seq',
  GREATEST((SELECT COALESCE(MAX(id), 0) FROM convo_requests), 1),
  (SELECT MAX(id) IS NOT NULL FROM convo_requests)
);
ALTER SEQUENCE convo_turn_id_seq OWNED BY convo_requests.turn_id;
