-- fit_suggestions: why a college fits the person, and which college, are typed
-- pii.*, and the table gains deleted_at (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- college_id keeps its foreign key to colleges: pii.uuid is a domain over UUID
-- and compares with uuid's own equality operator.

ALTER TABLE fit_suggestions
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ALTER COLUMN rationale  TYPE pii.text,
    ALTER COLUMN college_id TYPE pii.uuid,
    ALTER COLUMN rationale  DROP NOT NULL,
    ALTER COLUMN college_id DROP NOT NULL,
    ADD CONSTRAINT fit_suggestions_rationale_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR rationale IS NOT NULL),
    ADD CONSTRAINT fit_suggestions_college_id_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR college_id IS NOT NULL);
