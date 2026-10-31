-- money_profiles + _versions: the four money answers are typed pii.* (RFC 263,
-- slice deletion/01/the-classification). The shape is in classify-users.sql.
--
-- The answers are already nullable; their *_status columns are not personal
-- (whether a question was answered, not the answer). Each value_iff_answered
-- rule gains the erased arm, so a deleted profile may lose an answer and keep
-- "answered". The history table carries no CHECKs to change.

ALTER TABLE money_profiles
    ALTER COLUMN income_band     TYPE pii.text,
    ALTER COLUMN residency_state TYPE pii.text,
    ALTER COLUMN living_plan     TYPE pii.text,
    ALTER COLUMN dependency      TYPE pii.text,
    DROP CONSTRAINT money_profiles_income_band_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_residency_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_living_plan_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_dependency_value_iff_answered_check,
    ADD CONSTRAINT money_profiles_income_band_value_iff_answered_check
        CHECK ((deleted_at IS NOT NULL AND income_band IS NULL)
               OR value_iff_answered(income_band_status, income_band IS NOT NULL)),
    ADD CONSTRAINT money_profiles_residency_value_iff_answered_check
        CHECK ((deleted_at IS NOT NULL AND residency_state IS NULL)
               OR value_iff_answered(residency_status, residency_state IS NOT NULL)),
    ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check
        CHECK ((deleted_at IS NOT NULL AND living_plan IS NULL)
               OR value_iff_answered(living_plan_status, living_plan IS NOT NULL)),
    ADD CONSTRAINT money_profiles_dependency_value_iff_answered_check
        CHECK ((deleted_at IS NOT NULL AND dependency IS NULL)
               OR value_iff_answered(dependency_status, dependency IS NOT NULL));

ALTER TABLE money_profiles_versions
    ALTER COLUMN income_band     TYPE pii.text,
    ALTER COLUMN residency_state TYPE pii.text,
    ALTER COLUMN living_plan     TYPE pii.text,
    ALTER COLUMN dependency      TYPE pii.text;
