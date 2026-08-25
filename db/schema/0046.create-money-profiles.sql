-- Money profile (RFC 134): one row per student carrying two typed family-
-- finance facts -- the household income band (picks net_price_q1..q5, RFC 133)
-- and the two-letter state of residency (in/out-of-state tuition) -- each with
-- a tri-state answer status (unanswered | answered | declined) so "asked and
-- declined" is a schema fact the coach can see and respect.
--
-- Versioning composition mirrors college_list_entries (0024) exactly: a
-- money_profiles_versions history table, the shared trigger functions
-- (prevent_physical_delete, prevent_immutable_updates,
-- prevent_physical_timestamp_update, enforce_versioning, update_timestamp)
-- with no new function except this migration's own log_money_profile_version(),
-- soft-delete via deleted_at, and the 4-timestamp pattern.

-- ---------------------------------------------------------------------------
-- money_profiles -- versioned mutable entity
-- ---------------------------------------------------------------------------

CREATE TABLE money_profiles (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Household income band, enumerated with self-describing labels that
    -- name the Scorecard NPT4 brackets (RFC 133); the label -> net_price_qN
    -- mapping lives in code. TEXT + CHECK per the house enum pattern
    -- (claims.kind/status/topic, college_list_entries.status) -- the schema
    -- has no native PG ENUM types, and this stays consistent.
    income_band        TEXT NULL,
    income_band_status TEXT NOT NULL DEFAULT 'unanswered',

    -- Two-letter USPS state of residency (in/out-of-state tuition).
    residency_state    TEXT     NULL,
    residency_status   TEXT     NOT NULL DEFAULT 'unanswered',

    CONSTRAINT money_profiles_income_band_check
        CHECK (income_band IS NULL OR income_band IN
               ('under_30k','30k_to_48k','48k_to_75k','75k_to_110k','over_110k')),
    CONSTRAINT money_profiles_income_band_status_check
        CHECK (income_band_status IN ('unanswered','answered','declined')),
    CONSTRAINT money_profiles_residency_state_format_check
        CHECK (residency_state IS NULL OR residency_state ~ '^[A-Z]{2}$'),
    CONSTRAINT money_profiles_residency_status_check
        CHECK (residency_status IN ('unanswered','answered','declined')),
    -- Value present exactly when answered: a declined/unanswered field can
    -- never smuggle a stale value to a consumer.
    CONSTRAINT money_profiles_income_band_value_iff_answered_check
        CHECK ((income_band IS NOT NULL) = (income_band_status = 'answered')),
    CONSTRAINT money_profiles_residency_value_iff_answered_check
        CHECK ((residency_state IS NOT NULL) = (residency_status = 'answered'))
);

CREATE UNIQUE INDEX money_profiles_student_active_idx
    ON money_profiles (student_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- money_profiles_versions -- version history
-- ---------------------------------------------------------------------------

CREATE TABLE money_profiles_versions (
  id                 UUID        NOT NULL REFERENCES money_profiles(id) ON DELETE RESTRICT,
  version            INTEGER     NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL,
  row_created_at     TIMESTAMPTZ NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL,
  row_updated_at     TIMESTAMPTZ NOT NULL,
  deleted_at         TIMESTAMPTZ NULL,
  student_id         UUID        NOT NULL,
  income_band        TEXT        NULL,
  income_band_status TEXT        NOT NULL,
  residency_state    TEXT        NULL,
  residency_status   TEXT        NOT NULL,
  PRIMARY KEY (id, version)
);

CREATE INDEX money_profiles_versions_id_updated_at_idx ON money_profiles_versions (id, updated_at);

CREATE OR REPLACE FUNCTION log_money_profile_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO money_profiles_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, income_band, income_band_status, residency_state, residency_status
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.income_band, NEW.income_band_status, NEW.residency_state, NEW.residency_status
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- money_profiles triggers
-- ---------------------------------------------------------------------------
-- BEFORE triggers fire in trigger-name order: 00 delete-guard, 00a
-- immutable-guard, 00b row_created_at-guard, 01 versioning, 03 updated_at.

CREATE TRIGGER trigger_00_prevent_money_profiles_physical_delete
BEFORE DELETE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_money_profiles_immutable_updates
BEFORE UPDATE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

-- Reuses the existing non-suffixed shared trigger name verbatim (the
-- college_list_entries precedent, 0024); money_profiles is just the next table
-- with a row_created_at column, not a name variant.
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_01_enforce_money_profiles_versioning
BEFORE INSERT OR UPDATE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_money_profiles_updated_at
BEFORE UPDATE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_money_profile_version
AFTER INSERT OR UPDATE ON money_profiles
FOR EACH ROW
EXECUTE PROCEDURE log_money_profile_version();
