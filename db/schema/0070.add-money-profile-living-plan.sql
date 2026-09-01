-- Where the student plans to live, on the money profile. RFC 152.
--
-- The third tri-state money-profile field, on the same shape as income_band
-- (0046) and residency_state: a value column plus a status column
-- (unanswered | answered | declined) and a value-IFF-answered CHECK, so
-- "asked and declined" stays a schema fact the coach can see and respect,
-- and a declined field can never smuggle a stale value to a consumer.
--
-- The three values are the arrangement wire names the cost surfaces already
-- speak (RFC 149 LivingArrangement): on_campus, off_campus, with_family.
-- One vocabulary, one enum -- LivingArrangement moves to db/models in this
-- RFC and is the only Kotlin home for these strings.
--
-- This is the "add columns to a versioned table" shape of 0045: the value
-- columns land on both the entity and its history table, and the history
-- writer is redefined with CREATE OR REPLACE so the existing
-- trigger_04_log_money_profile_version picks it up by name with no re-wiring
-- (the 0023 pattern).

ALTER TABLE money_profiles
    ADD COLUMN living_plan        TEXT NULL,
    ADD COLUMN living_plan_status TEXT NOT NULL DEFAULT 'unanswered',
    ADD CONSTRAINT money_profiles_living_plan_check
        CHECK (living_plan IS NULL OR living_plan IN
               ('on_campus','off_campus','with_family')),
    ADD CONSTRAINT money_profiles_living_plan_status_check
        CHECK (living_plan_status IN ('unanswered','answered','declined')),
    -- Value present exactly when answered: a declined/unanswered field can
    -- never smuggle a stale value to a consumer.
    ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check
        CHECK ((living_plan IS NOT NULL) = (living_plan_status = 'answered'));

-- The history table takes the same pair. living_plan_status is NOT NULL there
-- too, so it needs the DEFAULT or every pre-existing history row fails the
-- ALTER (and would then break parseStatus in the admin history panel, which
-- reads versions rows through the same status parser).
ALTER TABLE money_profiles_versions
    ADD COLUMN living_plan        TEXT NULL,
    ADD COLUMN living_plan_status TEXT NOT NULL DEFAULT 'unanswered';

-- Redefine the history writer to carry the new columns.
CREATE OR REPLACE FUNCTION log_money_profile_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO money_profiles_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, income_band, income_band_status, residency_state, residency_status,
        living_plan, living_plan_status
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.income_band, NEW.income_band_status, NEW.residency_state, NEW.residency_status,
        NEW.living_plan, NEW.living_plan_status
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
