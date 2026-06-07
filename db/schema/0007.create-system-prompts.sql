-- System prompts: the immutable, insert-only catalog of team-authored prompts
-- that shape every coaching turn, and the rewire pinning each convo_requests
-- turn to a specific prompt by foreign key. RFC 33.
--
-- system_prompts is an immutable entity (postgres-entity-table-design, immutable
-- variant): a row is created once and never updated or deleted. A "new version"
-- of a prompt is simply a new immutable row with a new id; there is no version
-- OCC column, no *_versions table, and no log trigger. The id is a complete,
-- permanent pin that resolves forever to the exact (name, version, body) sent.

-- ---------------------------------------------------------------------------
-- Immutable-entity guard functions (new shared functions).
--
-- 0000.shared-functions.sql is already applied and append-only; bin/db-migrate
-- skips files whose version_id is already recorded, so editing 0000 would not
-- re-run on existing databases. These new guards are therefore defined here
-- (idempotent CREATE OR REPLACE). They are distinct from every existing guard,
-- each of which carries a message wrong for an immutable entity:
-- prevent_immutable_updates() blocks only id/created_at/row_created_at (partial);
-- prevent_physical_delete() advises a non-existent deleted_at; and
-- prevent_log_update/prevent_log_delete mislabel an entity as a "log" and tell
-- the operator to prune by partition/retention (this is permanent reference
-- data, never pruned).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_immutable_entity_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Immutable entity rows cannot be updated.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_immutable_entity_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Immutable entity rows cannot be deleted.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- system_prompts — immutable entity
-- ---------------------------------------------------------------------------

CREATE TABLE system_prompts (
  id             UUID NOT NULL PRIMARY KEY DEFAULT uuidv7(),

  -- Creation timestamps only (immutable: no update timestamps). created_at is
  -- the logical authoring time, row_created_at the physical insert time; they
  -- differ only when a prompt is backfilled carrying its original authoring date.
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Domain data, all immutable.
  name    TEXT NOT NULL,   -- logical family/key, e.g. 'coach'
  version TEXT NOT NULL,   -- label carried as plain immutable data, e.g. 'v1' / '2026-06-01'
  body    TEXT NOT NULL,   -- the exact prompt text sent to the model

  -- A (family, version) pair maps to exactly one immutable body forever. This is
  -- what makes "a new version is a new row" safe: two different bodies cannot both
  -- claim to be coach/v1.
  CONSTRAINT system_prompts_name_version_unique UNIQUE (name, version),

  -- name/version: bounded, non-empty, canonical (trimmed) — the project-wide
  -- TEXT + named-CHECK text-column convention (postgres-entity-table-design,
  -- "String Types"; see D-5 for why body is exempt from the trimmed check).
  CONSTRAINT system_prompts_name_length_check     CHECK (length(name) <= 255),
  CONSTRAINT system_prompts_name_not_empty_check  CHECK (length(trim(name)) > 0),
  CONSTRAINT system_prompts_name_trimmed_check    CHECK (name = trim(name)),
  CONSTRAINT system_prompts_version_length_check    CHECK (length(version) <= 255),
  CONSTRAINT system_prompts_version_not_empty_check CHECK (length(trim(version)) > 0),
  CONSTRAINT system_prompts_version_trimmed_check   CHECK (version = trim(version)),

  -- body: non-empty and size-bounded, but NOT trimmed. The body is the verbatim
  -- artifact sent to the model and has no raw-payload backup table behind it;
  -- trailing whitespace/newlines may be intentional, so canonicalizing it would
  -- risk altering what reaches the LLM (see D-5).
  CONSTRAINT system_prompts_body_not_empty_check CHECK (length(trim(body)) > 0),
  CONSTRAINT system_prompts_body_size_check      CHECK (octet_length(body) <= 1048576)
);

-- No explicit secondary index: UNIQUE (name, version) creates a composite index
-- whose leading name column also serves "all versions of a family" lookups; the
-- PK serves id lookups (the FK join path).

-- BEFORE triggers fire in alphabetical name order (00 / 01 convention).
CREATE TRIGGER trigger_00_prevent_system_prompts_update
BEFORE UPDATE ON system_prompts
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_entity_update();

CREATE TRIGGER trigger_01_prevent_system_prompts_delete
BEFORE DELETE ON system_prompts
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_entity_delete();

-- The blanket BEFORE UPDATE block makes id, created_at, and row_created_at
-- immutable for free — no separate prevent_immutable_updates guard is needed.

-- ---------------------------------------------------------------------------
-- convo_requests rewire — replace the loose verbatim system_prompt_version
-- TEXT pin (RFC 32 D-2) with a single system_prompt_id FK to the immutable
-- catalog. DROP COLUMN automatically drops the three CHECK constraints that
-- depended on system_prompt_version. The ADD COLUMN ... NOT NULL with no
-- default is valid because convo_requests is empty (RFC 32 is schema-only).
--
-- ON DELETE RESTRICT, deliberately NOT CASCADE: system_prompt_id points at a
-- shared parent; cascading a prompt delete would erase every turn that used it
-- across all students. RESTRICT forbids deleting a prompt while any turn cites
-- it — a second, declarative line of defense behind the immutable-entity delete
-- guard.
-- ---------------------------------------------------------------------------

ALTER TABLE convo_requests
  DROP COLUMN system_prompt_version,
  ADD COLUMN system_prompt_id UUID NOT NULL
    REFERENCES system_prompts(id) ON DELETE RESTRICT;
