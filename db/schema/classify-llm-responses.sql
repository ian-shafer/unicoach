-- llm_responses: what the model said, and the reason a call did not complete,
-- are typed pii.*, and the log gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- Both columns are already nullable, and each is tied to the outcome by a rule
-- that gains the erased arm, so a deleted completed response may lose its
-- content and keep its outcome, model, tokens and cost.

ALTER TABLE llm_responses
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN content TYPE pii.jsonb,
    ALTER COLUMN reason  TYPE pii.text,
    DROP CONSTRAINT llm_responses_completed_content_check,
    DROP CONSTRAINT llm_responses_reason_presence_check,
    ADD CONSTRAINT llm_responses_completed_content_check
        CHECK ((deleted_at IS NOT NULL AND content IS NULL)
               OR ((content IS NOT NULL) = (outcome = 'completed'))),
    ADD CONSTRAINT llm_responses_reason_presence_check
        CHECK ((deleted_at IS NOT NULL AND reason IS NULL)
               OR ((reason IS NULL) = (outcome = 'completed')));
