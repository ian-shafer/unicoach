-- admission_test_scores + _versions: the score and the sitting date are typed
-- pii.* (RFC 263, slice deletion/01/the-classification). The shape is in
-- classify-users.sql.
--
-- test, component and taken_precision are not personal: our vocabulary and the
-- precision the date was given at. The scale and period-start CHECKs already
-- admit NULL (a comparison or is_period_start on NULL is not false).

ALTER TABLE admission_test_scores
    ALTER COLUMN score    TYPE pii.integer,
    ALTER COLUMN taken_on TYPE pii.date,
    ALTER COLUMN score    DROP NOT NULL,
    ALTER COLUMN taken_on DROP NOT NULL,
    ADD CONSTRAINT admission_test_scores_score_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR score IS NOT NULL),
    ADD CONSTRAINT admission_test_scores_taken_on_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR taken_on IS NOT NULL);

ALTER TABLE admission_test_scores_versions
    ALTER COLUMN score    TYPE pii.integer,
    ALTER COLUMN taken_on TYPE pii.date,
    ALTER COLUMN score    DROP NOT NULL,
    ALTER COLUMN taken_on DROP NOT NULL,
    ADD CONSTRAINT admission_test_scores_score_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR score IS NOT NULL),
    ADD CONSTRAINT admission_test_scores_taken_on_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR taken_on IS NOT NULL);
