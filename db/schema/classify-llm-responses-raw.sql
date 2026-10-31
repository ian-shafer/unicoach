-- llm_responses_raw: the provider's verbatim reply is typed pii.*, and the log
-- gains deleted_at (RFC 263, slice deletion/01/the-classification). The shape
-- is in classify-users.sql.

ALTER TABLE llm_responses_raw
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN payload TYPE pii.jsonb,
    ALTER COLUMN payload DROP NOT NULL,
    ADD CONSTRAINT llm_responses_raw_payload_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR payload IS NOT NULL);
