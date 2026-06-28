-- Versioned colleges + read-only admin browsing. RFC 82.
--
-- Makes `colleges` a versioned reference entity using the house versioning
-- mechanism (`enforce_versioning()` + a `colleges_versions` history table, the
-- same pieces `users`/`students` use) composed à la carte WITHOUT soft-delete.
-- The version writer is the Scorecard ingest upsert (not an OCC request path):
-- the `ON CONFLICT DO UPDATE` branch bumps `version` and logs history only on an
-- actual content change. `colleges` carries only logical created_at/updated_at,
-- so it has no physical audit clock to guard and no soft-delete column.
--
-- This migration also introduces three shared trigger functions (the first
-- consumer of each) and narrows the existing `prevent_immutable_updates()`:
--   prevent_delete()                      — generic BEFORE DELETE guard for a
--                                           versioned mutable entity that is
--                                           neither soft-deletable nor a log.
--   log_college_version()                 — AFTER INSERT/UPDATE history writer.
--   prevent_physical_timestamp_update()   — carries the `row_created_at`
--                                           immutability guarantee for the five
--                                           tables that have that column.
--   prevent_immutable_updates()           — REDEFINED to guard `id`+`created_at`
--                                           only, dropping the static
--                                           `row_created_at` reference so it is
--                                           attachable to a table (colleges)
--                                           without the row_* split.

-- ---------------------------------------------------------------------------
-- Shared trigger functions
-- ---------------------------------------------------------------------------

-- Generic BEFORE DELETE guard. Honest for a versioned mutable entity that is
-- neither soft-deletable (prevent_physical_delete names a deleted_at column
-- absent here) nor a log (prevent_log_delete is retention).
CREATE OR REPLACE FUNCTION prevent_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Deletions are blocked on this table.' USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

-- AFTER INSERT/UPDATE history writer for colleges. Inserts NEW's curated columns
-- plus version/created_at/updated_at into colleges_versions. Mirrors
-- log_user_version().
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment, admission_rate,
        sat_avg, cost_attendance, net_price, tuition_in_state, tuition_out_state,
        graduation_rate, median_earnings, pct_pell, website, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment, NEW.admission_rate, NEW.sat_avg,
        NEW.cost_attendance, NEW.net_price, NEW.tuition_in_state,
        NEW.tuition_out_state, NEW.graduation_rate, NEW.median_earnings,
        NEW.pct_pell, NEW.website, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Carries the row_created_at immutability guarantee that prevent_immutable_updates()
-- no longer enforces (see the redefinition below). Attached to the five tables
-- that have the column.
CREATE OR REPLACE FUNCTION prevent_physical_timestamp_update()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.row_created_at IS DISTINCT FROM OLD.row_created_at THEN
        RAISE EXCEPTION 'The row_created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Redefine prevent_immutable_updates() to guard id + created_at ONLY (the
-- immutable fields every entity has), dropping the static row_created_at
-- reference so it is attachable to a table without the row_* split (colleges).
-- 0000.shared-functions.sql is an applied, immutable migration; CREATE OR
-- REPLACE here is picked up by every existing trigger by name with no re-wiring.
-- Behavior for id/created_at is unchanged on all tables that already use it; the
-- dropped row_created_at arm is preserved verbatim by
-- prevent_physical_timestamp_update() on the same five tables.
CREATE OR REPLACE FUNCTION prevent_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'The id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The created_at field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- colleges.version column
-- ---------------------------------------------------------------------------

-- Trigger-managed, never client-supplied (mirrors users.version). Existing rows
-- become version = 1.
ALTER TABLE colleges ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

-- ---------------------------------------------------------------------------
-- colleges_versions — version history
-- ---------------------------------------------------------------------------

-- Records every committed change to a colleges row, mirroring users_versions
-- minus the deleted_at and row_* columns colleges does not have. No secondary
-- index: listVersions's WHERE id = ? ORDER BY version is served by the leftmost
-- prefix of the (id, version) primary key. FK is ON DELETE RESTRICT (mirrors
-- users_versions); combined with prevent_delete() on colleges, history can never
-- be orphaned.
CREATE TABLE colleges_versions (
    id                   UUID              NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT,
    version              INTEGER           NOT NULL,
    unit_id              INTEGER           NOT NULL,
    opeid                TEXT              NULL,
    name                 TEXT              NOT NULL,
    city                 TEXT              NOT NULL,
    state                TEXT              NOT NULL,
    region               SMALLINT          NULL,
    locale               SMALLINT          NULL,
    latitude             DOUBLE PRECISION  NULL,
    longitude            DOUBLE PRECISION  NULL,
    control              SMALLINT          NOT NULL,
    undergrad_enrollment INTEGER           NULL,
    admission_rate       DOUBLE PRECISION  NULL,
    sat_avg              INTEGER           NULL,
    cost_attendance      INTEGER           NULL,
    net_price            INTEGER           NULL,
    tuition_in_state     INTEGER           NULL,
    tuition_out_state    INTEGER           NULL,
    graduation_rate      DOUBLE PRECISION  NULL,
    median_earnings      INTEGER           NULL,
    pct_pell             DOUBLE PRECISION  NULL,
    website              TEXT              NULL,
    created_at           TIMESTAMPTZ       NOT NULL,
    updated_at           TIMESTAMPTZ       NOT NULL,
    PRIMARY KEY (id, version)
);

-- Backfill one v1 history row per existing college BEFORE the log trigger is
-- attached, so the AFTER trigger does not double-write during this migration.
INSERT INTO colleges_versions (
    id, version, unit_id, opeid, name, city, state, region, locale, latitude,
    longitude, control, undergrad_enrollment, admission_rate, sat_avg,
    cost_attendance, net_price, tuition_in_state, tuition_out_state,
    graduation_rate, median_earnings, pct_pell, website, created_at, updated_at
)
SELECT
    id, version, unit_id, opeid, name, city, state, region, locale, latitude,
    longitude, control, undergrad_enrollment, admission_rate, sat_avg,
    cost_attendance, net_price, tuition_in_state, tuition_out_state,
    graduation_rate, median_earnings, pct_pell, website, created_at, updated_at
FROM colleges;

-- ---------------------------------------------------------------------------
-- colleges triggers
-- ---------------------------------------------------------------------------
-- BEFORE triggers fire in trigger-name order, so 00 delete-guard precedes 00a
-- immutable-guard precedes 01 versioning. colleges deliberately has NO
-- trigger_00b_prevent_physical_timestamp_update and NO deleted_at: it carries
-- only logical created_at/updated_at, so there is no physical audit clock to
-- guard. The existing trigger_03_enforce_colleges_updated_at (from RFC 67,
-- touching only updated_at) is retained unchanged.

CREATE TRIGGER trigger_00_prevent_colleges_delete
BEFORE DELETE ON colleges
FOR EACH ROW
EXECUTE PROCEDURE prevent_delete();

CREATE TRIGGER trigger_00a_prevent_colleges_immutable_updates
BEFORE UPDATE ON colleges
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_01_enforce_colleges_versioning
BEFORE INSERT OR UPDATE ON colleges
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

-- This AFTER row trigger is what keeps colleges_versions from gaining a spurious
-- row on an unchanged re-ingest. The ingest upsert's `ON CONFLICT DO UPDATE ...
-- WHERE <content changed>` clause suppresses the UPDATE when nothing changed, and
-- PostgreSQL does NOT fire an AFTER row trigger for a suppressed (no-op) update --
-- so log_college_version() simply never runs on the no-op re-ingest path.
CREATE TRIGGER trigger_04_log_college_version
AFTER INSERT OR UPDATE ON colleges
FOR EACH ROW
EXECUTE PROCEDURE log_college_version();

-- ---------------------------------------------------------------------------
-- trigger_00b_prevent_physical_timestamp_update on the five row_created_at tables
-- ---------------------------------------------------------------------------
-- Preserves the loud row_created_at immutability guarantee that
-- prevent_immutable_updates() no longer enforces, exactly, on the tables that
-- attach it and have the column: users, sessions, students, convos, claims.

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON sessions
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON students
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON convos
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON claims
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();
