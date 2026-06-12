-- Widen the convo_requests.provider allowlist to admit 'log', the identity of
-- the chat module's no-network stub adapter (LogOnlyChatProvider). RFC 43.
--
-- The coaching service writes ChatProvider.id verbatim into this column, and
-- the stack must run end-to-end in dev and test against the stub, so the
-- stub's identity must be insertable. Widening keeps provenance truthful
-- ('log' rows are visibly synthetic) and follows 0006's own comment: "Extend
-- the list in a later migration as providers are added."
--
-- DDL is unaffected by the table's append-only row triggers, and the
-- constraint name is reused so bin/db-convos-tests assertions keyed on it
-- keep matching.

ALTER TABLE convo_requests
  DROP CONSTRAINT convo_requests_provider_valid_check;
ALTER TABLE convo_requests
  ADD CONSTRAINT convo_requests_provider_valid_check
    CHECK (provider IN ('anthropic', 'log'));
