-- Whether the student is dependent or independent for federal aid, on the
-- money profile. RFC 159.
--
-- The fourth tri-state money-profile field, on the 0070 shape exactly: a value
-- column plus a status column (unanswered | answered | declined) and a
-- value-IFF-answered CHECK, so "asked and declined" stays a schema fact the
-- coach can see and respect, and a declined field can never smuggle a stale
-- value to a consumer.
--
-- The answer selects which Direct Loan limit table and which max-Pell income
-- test apply (RFC 159 D-F) -- it visibly changes what the federal-aid surface
-- says, which is what makes it worth asking at all.
--
-- Column naming: the fact's own federal name is "dependency status", but the
-- house tri-state pair is <field> + <field>_status (income_band/
-- income_band_status, living_plan/living_plan_status), so the value column is
-- `dependency` and the status column `dependency_status` -- naming the value
-- column dependency_status would force a dependency_status_status stutter and
-- break the one shape every reader of this table parses.
--
-- The two values are the federal vocabulary (RFC 159 DependencyStatus, the
-- only Kotlin home for these strings): dependent, independent.
--
-- The history writer is redefined with CREATE OR REPLACE so the existing
-- trigger_04_log_money_profile_version picks it up by name with no re-wiring
-- (the 0023/0070 pattern).

ALTER TABLE money_profiles
    ADD COLUMN dependency        TEXT NULL,
    ADD COLUMN dependency_status TEXT NOT NULL DEFAULT 'unanswered',
    ADD CONSTRAINT money_profiles_dependency_check
        CHECK (dependency IS NULL OR dependency IN ('dependent','independent')),
    ADD CONSTRAINT money_profiles_dependency_status_check
        CHECK (dependency_status IN ('unanswered','answered','declined')),
    -- Value present exactly when answered: a declined/unanswered field can
    -- never smuggle a stale value to a consumer.
    ADD CONSTRAINT money_profiles_dependency_value_iff_answered_check
        CHECK ((dependency IS NOT NULL) = (dependency_status = 'answered'));

-- The history table takes the same pair. dependency_status is NOT NULL there
-- too, so it needs the DEFAULT or every pre-existing history row fails the
-- ALTER (and would then break parseStatus in the admin history panel, which
-- reads versions rows through the same status parser).
ALTER TABLE money_profiles_versions
    ADD COLUMN dependency        TEXT NULL,
    ADD COLUMN dependency_status TEXT NOT NULL DEFAULT 'unanswered';

-- Redefine the history writer to carry the new columns.
CREATE OR REPLACE FUNCTION log_money_profile_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO money_profiles_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, income_band, income_band_status, residency_state, residency_status,
        living_plan, living_plan_status, dependency, dependency_status
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.income_band, NEW.income_band_status, NEW.residency_state, NEW.residency_status,
        NEW.living_plan, NEW.living_plan_status, NEW.dependency, NEW.dependency_status
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
