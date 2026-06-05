CREATE TABLE students (
  id UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version INTEGER NOT NULL DEFAULT 1,

  -- Timestamps (4-timestamp advanced pattern)
  created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  row_updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMPTZ NULL,

  -- Ownership
  user_id UUID NOT NULL REFERENCES users(id),

  -- Variable-precision expected high-school graduation date
  expected_high_school_graduation_year  SMALLINT NOT NULL,
  expected_high_school_graduation_month SMALLINT NULL,
  expected_high_school_graduation_day   SMALLINT NULL,

  CONSTRAINT grad_month_range CHECK (
    expected_high_school_graduation_month BETWEEN 1 AND 12),
  CONSTRAINT grad_day_requires_month CHECK (
    expected_high_school_graduation_day IS NULL
    OR expected_high_school_graduation_month IS NOT NULL),
  CONSTRAINT grad_date_valid CHECK (
    expected_high_school_graduation_day IS NULL
    OR make_date(
         expected_high_school_graduation_year::int,
         expected_high_school_graduation_month::int,
         expected_high_school_graduation_day::int) IS NOT NULL)
);

-- One student per user, total (NOT partial): a user_id can never appear twice,
-- even across soft-deletes. Account deletion cascades to the user, so there is no
-- legitimate re-creation path.
CREATE UNIQUE INDEX students_user_id_unique_idx ON students (user_id);

CREATE TABLE students_versions (
  id UUID NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
  version INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ NULL,
  user_id UUID NOT NULL,
  expected_high_school_graduation_year  SMALLINT NOT NULL,
  expected_high_school_graduation_month SMALLINT NULL,
  expected_high_school_graduation_day   SMALLINT NULL,
  PRIMARY KEY (id, version)
);

-- Index for efficient date-range timeline queries on a specific student's history
CREATE INDEX students_versions_id_updated_at_idx ON students_versions (id, updated_at);

CREATE OR REPLACE FUNCTION log_student_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO students_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        user_id,
        expected_high_school_graduation_year,
        expected_high_school_graduation_month,
        expected_high_school_graduation_day
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.user_id,
        NEW.expected_high_school_graduation_year,
        NEW.expected_high_school_graduation_month,
        NEW.expected_high_school_graduation_day
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- BEFORE triggers execute in alphabetical order by name if not specified.
CREATE TRIGGER trigger_00_prevent_students_physical_delete
BEFORE DELETE ON students
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_students_immutable_updates
BEFORE UPDATE ON students
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_01_enforce_students_versioning
BEFORE INSERT OR UPDATE ON students
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_students_updated_at
BEFORE UPDATE ON students
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

-- AFTER trigger to log the finalized row
CREATE TRIGGER trigger_04_log_student_version
AFTER INSERT OR UPDATE ON students
FOR EACH ROW
EXECUTE PROCEDURE log_student_version();
