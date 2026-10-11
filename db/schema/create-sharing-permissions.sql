-- Sharing permissions: what a student allows the people they share with to see
-- of their own data. RFC 248, product slice beyond/10/one-place-for-permissions
-- (brief 0011, D35).
--
-- One row per (student, permission). A permission names the DATA, not the page
-- it shows on: `intended_subjects` is "people I share with may see what I want
-- to study", and every surface that shows that data to someone else honours the
-- same answer.
--
-- Nothing reads this table yet, on purpose. The first reader is the Family Cost
-- Report (beyond/07/the-report-weave), which reads it inside the report's one
-- session when it renders.
--
-- Three states, two of them stored:
--   no row    — never asked. Reads as not granted: silence is not consent.
--   granted   — the student allowed it.
--   refused   — the student said no, or withdrew an earlier grant.
-- `granted` <-> `refused` is an ordinary versioned UPDATE, reversible both ways
-- forever. Nothing is set once and nothing is append-only.
--
-- The table follows the `intended_subject_status` composition (RFC 205)
-- exactly: uuidv7 primary key, version, the four timestamps, deleted_at soft
-- delete, a *_versions history table, and the shared triggers by name. No
-- shared function is added or redefined; the two functions this migration
-- defines in place are its own, exactly as intended-subjects.sql defines its
-- own.

-- ---------------------------------------------------------------------------
-- sharing_permissions — one student's answer to one permission
--
-- `permission` and `status` are each TEXT + ONE named CHECK, mirrored by exactly
-- one Kotlin enum each (SharingPermission, SharingPermissionStatus). A
-- permission nobody reviewed cannot be written: widening the list is a
-- migration that widens the CHECK.
--
-- The stored words avoid `withheld` (already a cost word on the page the first
-- reader renders) and avoid `answer_status` (`declined` is a refusal to ANSWER a
-- question, not a refusal to ALLOW).
-- ---------------------------------------------------------------------------

CREATE TABLE sharing_permissions (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    permission TEXT NOT NULL,
    status     TEXT NOT NULL,

    CONSTRAINT sharing_permissions_permission_check
        CHECK (permission IN ('intended_subjects')),
    CONSTRAINT sharing_permissions_status_check
        CHECK (status IN ('granted', 'refused'))
);

-- One ACTIVE row per (student, permission). The write path's
-- `ON CONFLICT ... DO NOTHING` names this partial index as its conflict target.
CREATE UNIQUE INDEX sharing_permissions_student_permission_active_idx
    ON sharing_permissions (student_id, permission) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- History: every committed state is snapshotted, INCLUDING `deleted_at`, so
-- "when did she allow this, and when did she take it back" stays answerable.
-- The history table carries no CHECKs: a history row mirrors whatever was live
-- and is never validated independently.
-- ---------------------------------------------------------------------------

CREATE TABLE sharing_permissions_versions (
    id             UUID        NOT NULL REFERENCES sharing_permissions(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    student_id     UUID        NOT NULL,
    permission     TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX sharing_permissions_versions_id_updated_at_idx
    ON sharing_permissions_versions (id, updated_at);

-- ---------------------------------------------------------------------------
-- This migration's own guards. `prevent_immutable_updates()` is SHARED and, as
-- redefined by 0023, guards `id` and `created_at` ONLY. Freezing a business
-- column therefore needs this migration's own function.
--
-- `student_id` and `permission` never change after insert: a row is one
-- student's answer to one permission, and moving it to another student or
-- another permission would rewrite that answer's history. `status` is exactly
-- what is meant to change.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_sharing_permission_column_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'student_id',
                  DETAIL = format('sharing_permissions row [%s] student_id [%s] -> [%s]', OLD.id, OLD.student_id, NEW.student_id);
    END IF;
    IF NEW.permission IS DISTINCT FROM OLD.permission THEN
        RAISE EXCEPTION 'The permission field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'permission',
                  DETAIL = format('sharing_permissions row [%s] permission [%s] -> [%s]', OLD.id, OLD.permission, NEW.permission);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_sharing_permission_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO sharing_permissions_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, student_id, permission, status
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.student_id, NEW.permission, NEW.status
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Triggers
--
-- BEFORE triggers fire in trigger-name order, intended_subject_status's order
-- verbatim: 00 delete-guard, 00a shared immutable-guard, 00b
-- row_created_at-guard, 00c this table's column guard, 01 versioning, 03
-- updated_at; then 04 AFTER INSERT OR UPDATE snapshot.
-- ---------------------------------------------------------------------------

CREATE TRIGGER trigger_00_prevent_sharing_permissions_physical_delete
BEFORE DELETE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_sharing_permissions_immutable_updates
BEFORE UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_sharing_permissions_column_updates
BEFORE UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE prevent_sharing_permission_column_updates();

CREATE TRIGGER trigger_01_enforce_sharing_permissions_versioning
BEFORE INSERT OR UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_sharing_permissions_updated_at
BEFORE UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_sharing_permission_version
AFTER INSERT OR UPDATE ON sharing_permissions
FOR EACH ROW EXECUTE PROCEDURE log_sharing_permission_version();
