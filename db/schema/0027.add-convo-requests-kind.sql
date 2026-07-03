-- Add convo_requests.kind: why a request row exists. RFC 94 (chat tool-use loop).
--
-- 'user' is real student input — made visible and extractable. 'tool_result' is
-- a synthetic loop continuation carrying tool_result blocks — excluded from
-- every projection (model replay, REST transcript, extraction). The row states
-- its own kind explicitly rather than being inferred positionally from a
-- neighbor's stop_reason, so the projection, extraction, and any future reader
-- filter on it without reconstructing turn order.
--
-- Additive and safe to roll back with the code: DEFAULT 'user' backfills every
-- existing row correctly (each current request is a user turn), and the loop is
-- extra behavior over the single-call path, so reverting the code leaves all
-- persisted rows readable (every historical row is a 'user' turn) with no data
-- migration.
--
-- The allowlist follows the provider-column TEXT+CHECK convention (0006/0009)
-- and extends in a later migration if another synthetic kind appears. No index:
-- readers already scan a convo's turns by convo_id; kind is a per-row filter,
-- not a lookup key. DDL is unaffected by the table's append-only row triggers.

ALTER TABLE convo_requests
  ADD COLUMN kind TEXT NOT NULL DEFAULT 'user';
ALTER TABLE convo_requests
  ADD CONSTRAINT convo_requests_kind_valid_check CHECK (kind IN ('user', 'tool_result'));
