-- llm_requests: what we sent the model about the person -- the system prompt
-- as rendered, the turn content, the tools and the parameters -- is typed
-- pii.*, and the log gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- tool_choice is not personal: it names one of our own tools. The table is
-- reached by erasure through the owned.llm_request_id keys
-- (classify-owned-llm-request-keys.sql), not child-ward.

ALTER TABLE llm_requests
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN system  TYPE pii.text,
    ALTER COLUMN content TYPE pii.jsonb,
    ALTER COLUMN tools   TYPE pii.jsonb,
    ALTER COLUMN params  TYPE pii.jsonb,
    ALTER COLUMN content DROP NOT NULL,
    ADD CONSTRAINT llm_requests_content_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR content IS NOT NULL);
