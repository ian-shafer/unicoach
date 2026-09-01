-- Per-college living-plan override on the college list. RFC 152 D2a.
--
-- A living plan is two facts wearing one name. Preference -- "we'd rather he
-- lived at home" -- is global and lives on money_profiles (0070). Feasibility
-- -- "he can only live at home if the school is commutable" -- is a fact about
-- the student-college PAIR, and lives here.
--
-- One column, not a pair: NULL IS "no override, use the global default". The
-- entry has nothing to decline (a decline is a global stance), so no second
-- tri-state is needed. Resolution is override -> default -> show all three,
-- and it lives in exactly one helper in :service.
--
-- Same values, same vocabulary as 0070; college_list_entries has not been
-- altered since 0024, so the history table and log_college_list_entry_version()
-- follow the 0045 ALTER shape.

ALTER TABLE college_list_entries
    ADD COLUMN living_plan TEXT NULL,
    ADD CONSTRAINT college_list_entries_living_plan_check
        CHECK (living_plan IS NULL OR living_plan IN
               ('on_campus','off_campus','with_family'));

ALTER TABLE college_list_entries_versions
    ADD COLUMN living_plan TEXT NULL;

-- Redefine the history writer to carry the new column. CREATE OR REPLACE is
-- picked up by the existing trigger_04_log_college_list_entry_version by name
-- with no re-wiring (the 0023 pattern).
CREATE OR REPLACE FUNCTION log_college_list_entry_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO college_list_entries_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, college_id, status, reasons, living_plan
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.college_id, NEW.status, NEW.reasons, NEW.living_plan
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
