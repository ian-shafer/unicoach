-- College list: a student-editable, per-college status + notes entity. RFC 91.
--
-- Two tables:
--   college_list_entries       — versioned mutable entity: a student's status
--                                 (considering/applying/admitted/rejected) and
--                                 free-text reasons for a specific college.
--   college_list_entry_support — append-only link log: many-to-many
--                                 college_list_entries<->observations, mirroring
--                                 claim_support exactly.
--
-- Reuses every shared trigger function from prior migrations
-- (prevent_physical_delete, prevent_immutable_updates,
-- prevent_physical_timestamp_update, enforce_versioning, update_timestamp,
-- prevent_log_update, prevent_log_delete) with no new function except this
-- migration's own log_college_list_entry_version(), defined in-place as 0023
-- did for its own new trigger functions.

-- ---------------------------------------------------------------------------
-- college_list_entries — versioned mutable entity
--
-- Mirrors students exactly: OCC version, the four-timestamp split
-- (created_at/row_created_at/updated_at/row_updated_at), and soft-delete via
-- deleted_at.
-- ---------------------------------------------------------------------------

CREATE TABLE college_list_entries (
  id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  version        INTEGER     NOT NULL DEFAULT 1,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at     TIMESTAMPTZ NULL,

  student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE RESTRICT, -- colleges are never deleted (prevent_delete, 0023); RESTRICT never fires

  status  TEXT NOT NULL DEFAULT 'considering',
  reasons TEXT NULL,

  CONSTRAINT college_list_entries_status_check CHECK (status IN ('considering','applying','admitted','rejected')),
  CONSTRAINT college_list_entries_reasons_length_check CHECK (reasons IS NULL OR length(reasons) <= 2048),
  CONSTRAINT college_list_entries_reasons_not_empty_check CHECK (reasons IS NULL OR length(trim(reasons)) > 0)
);

-- One active entry per (student, college); removing and re-adding later is a
-- new row, so a prior removal's history is never resurrected into it.
CREATE UNIQUE INDEX college_list_entries_student_college_active_idx
  ON college_list_entries (student_id, college_id) WHERE deleted_at IS NULL;

CREATE INDEX college_list_entries_student_id_idx ON college_list_entries (student_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- college_list_entries_versions — version history
-- ---------------------------------------------------------------------------

CREATE TABLE college_list_entries_versions (
  id             UUID        NOT NULL REFERENCES college_list_entries(id) ON DELETE RESTRICT,
  version        INTEGER     NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,
  row_created_at TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL,
  row_updated_at TIMESTAMPTZ NOT NULL,
  deleted_at     TIMESTAMPTZ NULL,
  student_id     UUID        NOT NULL,
  college_id     UUID        NOT NULL,
  status         TEXT        NOT NULL,
  reasons        TEXT        NULL,
  PRIMARY KEY (id, version)
);

CREATE INDEX college_list_entries_versions_id_updated_at_idx ON college_list_entries_versions (id, updated_at);

CREATE OR REPLACE FUNCTION log_college_list_entry_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO college_list_entries_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at, deleted_at,
        student_id, college_id, status, reasons
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at, NEW.deleted_at,
        NEW.student_id, NEW.college_id, NEW.status, NEW.reasons
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- college_list_entries triggers
-- ---------------------------------------------------------------------------
-- BEFORE triggers fire in trigger-name order: 00 delete-guard, 00a
-- immutable-guard, 00b row_created_at-guard, 01 versioning, 03 updated_at.

CREATE TRIGGER trigger_00_prevent_college_list_entries_physical_delete
BEFORE DELETE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_college_list_entries_immutable_updates
BEFORE UPDATE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE prevent_immutable_updates();

-- Reuses the existing non-suffixed shared trigger name verbatim (already
-- attached to users/sessions/students/convos/claims); college_list_entries is
-- the sixth table with a row_created_at column, not a name variant.
CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_01_enforce_college_list_entries_versioning
BEFORE INSERT OR UPDATE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_college_list_entries_updated_at
BEFORE UPDATE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_college_list_entry_version
AFTER INSERT OR UPDATE ON college_list_entries
FOR EACH ROW
EXECUTE PROCEDURE log_college_list_entry_version();

-- ---------------------------------------------------------------------------
-- college_list_entry_support — append-only link log
--
-- Mirrors claim_support exactly. An entry with no support rows is a
-- student-asserted fact with no cited utterance — the majority case for this
-- RFC's REST-only consumer, exactly as most claims cite no observation.
-- ---------------------------------------------------------------------------

CREATE TABLE college_list_entry_support (
  entry_id       UUID   NOT NULL REFERENCES college_list_entries(id) ON DELETE CASCADE,
  observation_id BIGINT NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (entry_id, observation_id)
);

CREATE INDEX college_list_entry_support_observation_idx ON college_list_entry_support (observation_id);

CREATE TRIGGER trigger_00_prevent_college_list_entry_support_update
BEFORE UPDATE ON college_list_entry_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();
CREATE TRIGGER trigger_01_prevent_college_list_entry_support_delete
BEFORE DELETE ON college_list_entry_support FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
