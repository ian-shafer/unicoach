-- Supporters and supporter_student_links: the family link spine. RFC 221,
-- product slice family/01/the-link (brief 0014).
--
-- Two tables, because there are two things:
--   supporters              — one row per adult user: the adult side as its own
--                             ENTITY, not a flag on `users` and not a property
--                             derived from a link row.
--   supporter_student_links — the relationship itself: a role-typed
--                             many-to-many from a supporter to a student.
--
-- Nothing reaches these tables yet, on purpose. A link row is only ever created
-- by accepting an invite (brief 0014 D2) and family/02/invite-and-accept builds
-- that door; this migration is the shape that door writes through.
--
-- Both tables follow the `students` composition (0005) and the
-- `intended_subjects` trigger set (RFC 205) exactly: uuidv7 primary key,
-- version, the four timestamps, deleted_at soft delete, a *_versions history
-- table, and the shared triggers by name. No shared function is added or
-- redefined; the functions this migration defines in place are its own, exactly
-- as 0024 and intended-subjects.sql define theirs.

-- ---------------------------------------------------------------------------
-- supporters -- the adult side, as its own entity.
--
-- It is an entity because it is a HOME for supporter-owned state that must not
-- sit on `users`: the supporter's own coach scope (family/06), and the meter
-- and billing answer (family/03.5). A link that pointed at `users` directly
-- would have to be migrated the moment that state arrived, with live rows in
-- the table.
-- ---------------------------------------------------------------------------

CREATE TABLE supporters (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    -- No ON DELETE action, exactly as students.user_id (0005) has none: users
    -- are never physically deleted (prevent_physical_delete), so a cascade
    -- would never fire and would only state a policy this table does not own.
    user_id UUID NOT NULL REFERENCES users(id)
);

-- One supporter per user, TOTAL (not partial): students_user_id_unique_idx
-- verbatim, and for its reason -- account deletion cascades the user, so there
-- is no legitimate re-creation path.
CREATE UNIQUE INDEX supporters_user_id_unique_idx ON supporters (user_id);

-- ---------------------------------------------------------------------------
-- supporter_student_links -- the relationship. References the SUPPORTER.
-- ---------------------------------------------------------------------------

CREATE TABLE supporter_student_links (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    -- RESTRICT is not spelled: supporters are never physically deleted either,
    -- so like the column above this one states no cascade policy.
    supporter_id UUID NOT NULL REFERENCES supporters(id),

    -- Every child of students cascades (0024, 0046, 0073, RFC 205).
    student_id   UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Four values. `supporter` is the catch-all and is not `other`.
    -- A single NAMED constraint, so retyping this column onto a shared DOMAIN
    -- later (RFC 209's pattern for a vocabulary two tables share) is a
    -- drop-constraint plus an alter-type with no data change. It is not a
    -- DOMAIN today because exactly one table speaks it.
    relationship TEXT NOT NULL,

    CONSTRAINT supporter_student_links_relationship_check
        CHECK (relationship IN ('parent','guardian','grandparent','supporter'))
);

-- ONE ACTIVE link per (supporter, student). PARTIAL, and that is the point:
-- unlink is a soft delete and re-linking must succeed (D2). `relationship` is
-- deliberately NOT in the key -- it describes the person, it does not make a
-- second link.
CREATE UNIQUE INDEX supporter_student_links_supporter_student_active_idx
    ON supporter_student_links (supporter_id, student_id)
    WHERE deleted_at IS NULL;

-- This student's supporters. The other read every later slice makes -- this
-- supporter's students -- needs no index of its own: the active-pair index
-- above already leads on supporter_id under the same predicate, so a
-- supporter_id index would be a redundant copy of its prefix.
CREATE INDEX supporter_student_links_student_idx
    ON supporter_student_links (student_id) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- History: students_versions / intended_subjects_versions verbatim -- every
-- live column with no defaults, PRIMARY KEY (id, version), an (id, updated_at)
-- index, and a RESTRICT foreign key back to the parent row.
--
-- `supporters` keeps one today for what it WILL hold: its settings, coach scope
-- and billing state all land on this table, and a history table added after
-- those columns exist starts its record at the migration rather than at the
-- row. `supporter_student_links` keeps one because `relationship` is
-- CORRECTABLE -- "she was recorded as a parent until March" is exactly the kind
-- of statement a history table exists to answer -- and because the snapshot
-- includes `deleted_at`, so an unlink is itself in the history and not merely
-- the last state before it.
-- ---------------------------------------------------------------------------

CREATE TABLE supporters_versions (
    id             UUID        NOT NULL REFERENCES supporters(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    user_id        UUID        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX supporters_versions_id_updated_at_idx
    ON supporters_versions (id, updated_at);

CREATE TABLE supporter_student_links_versions (
    id             UUID        NOT NULL REFERENCES supporter_student_links(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    supporter_id   UUID        NOT NULL,
    student_id     UUID        NOT NULL,
    relationship   TEXT        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX supporter_student_links_versions_id_updated_at_idx
    ON supporter_student_links_versions (id, updated_at);

-- ---------------------------------------------------------------------------
-- This migration's own guards. `prevent_immutable_updates()` is SHARED and, as
-- redefined by 0023, guards `id`, `created_at` and `row_created_at` ONLY -- it
-- cannot be given column names. Freezing a business column therefore needs this
-- migration's own function, defined in place exactly as 0023, 0024 and
-- intended-subjects.sql define theirs.
--
-- `supporters.user_id` is frozen: re-pointing a supporter row at another user
-- would reassign every link that row holds, silently.
--
-- `supporter_student_links.supporter_id` and `.student_id` are frozen:
-- re-pointing a link would make "this supporter has had access since March" a
-- statement about somebody else. A different pair is a different link.
-- `relationship` is deliberately NOT frozen -- a correction is a correction,
-- not a new relationship, and the history table records the old word.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_supporter_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'The user_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_supporter_student_link_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'The supporter_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Two rules about the two endpoints of a link: they are not the same person,
-- and they are both OPEN.
--
-- ONE FUNCTION PER RULE, because the two rules do not fire on the same events
-- and a reader must not have to find a `TG_OP` test inside a shared body to
-- learn that.
--
-- No self-link: a student is not their own supporter. The rule spans two tables
-- -- supporters.user_id against students.user_id -- so a CHECK cannot express
-- it, and it is enforced HERE rather than in family/02/invite-and-accept
-- because a constraint that lives in the only path which writes today is a
-- constraint the second writer will not have.
--
-- No link to a CLOSED endpoint: a foreign key proves that a supporter row and a
-- student row EXIST, and says nothing about whether either is still live. Both
-- tables soft-delete, so without this check a link to an account that was
-- closed years ago is a representable state, and every later slice of brief
-- 0014 authorises against these rows. An endpoint that is ABSENT is not this
-- guard's business: the foreign keys refuse that, with their own names.
--
-- The liveness half fires ON INSERT ONLY, and that is load-bearing. On UPDATE
-- it would refuse the unlink of a link whose student has since closed -- the
-- one write that row still needs -- and the row could never be cleaned up.
-- Minting a link to a closed account is the mistake; tidying one away is not.
-- The self-link half stays on INSERT OR UPDATE, because a rule about WHO the
-- endpoints are cannot start holding less on a later write.
--
-- BEFORE and not DEFERRED: both sides already exist before a link can reference
-- them, so there is no legitimate mid-transaction state in which the rows
-- disagree.
--
-- EVERY refusal names its own CONSTRAINT. `RAISE ... USING CONSTRAINT = ...`
-- populates the error's constraint field -- the same field a unique index or a
-- CHECK fills in, and the field mapChildWriteError and every DAO here already
-- route on. Without it all three refusals arrive as one bare P0001 and a caller
-- can only tell them apart by substring-matching English.
--
-- The three names are table-prefixed rule names in the house style, and end in
-- neither `_check` nor `_idx` ON PURPOSE: no pg_constraint or pg_index row
-- carries them, so a name borrowed from either catalogue would send a reader
-- looking for an object that is not there. The trigger is the object.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION prevent_supporter_student_link_self_link()
RETURNS TRIGGER AS $$
BEGIN
    -- PERFORM plus IF FOUND, not `SELECT ... INTO x` plus `IF x IS NOT NULL`:
    -- the question is whether a row exists, and FOUND answers exactly that.
    PERFORM 1
       FROM supporters sup
       JOIN students stu ON stu.user_id = sup.user_id
      WHERE sup.id = NEW.supporter_id
        AND stu.id = NEW.student_id;

    IF FOUND THEN
        RAISE EXCEPTION
            'A student may not be their own supporter: supporter [%] and student [%] are one user account.',
            NEW.supporter_id, NEW.student_id
            USING ERRCODE = 'P0001',
                  CONSTRAINT = 'supporter_student_links_not_self_link';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prevent_supporter_student_link_closed_endpoint()
RETURNS TRIGGER AS $$
BEGIN
    -- IF EXISTS per endpoint, one check each. The shape this replaces --
    -- `SELECT deleted_at IS NOT NULL INTO closed` and then `IF closed` -- read
    -- a THREE-valued result as two: an absent endpoint row leaves `closed`
    -- NULL, and `IF NULL` takes the OPEN branch, so the guard passed exactly
    -- the row it could say least about. It also reused one variable for both
    -- endpoints. EXISTS is a boolean by construction, and the absent endpoint
    -- stays the foreign key's to refuse, under the FK's own name.
    IF EXISTS (
        SELECT 1 FROM supporters sup
         WHERE sup.id = NEW.supporter_id AND sup.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'A link may not be created to a closed supporter: supporter [%], student [%].',
            NEW.supporter_id, NEW.student_id
            USING ERRCODE = 'P0001',
                  CONSTRAINT = 'supporter_student_links_supporter_live';
    END IF;

    IF EXISTS (
        SELECT 1 FROM students stu
         WHERE stu.id = NEW.student_id AND stu.deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'A link may not be created to a closed student: supporter [%], student [%].',
            NEW.supporter_id, NEW.student_id
            USING ERRCODE = 'P0001',
                  CONSTRAINT = 'supporter_student_links_student_live';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- A known limit of the self-link guard, recorded rather than fixed: students.user_id
-- is NOT frozen (0005 freezes id and created_at only), so re-pointing a student
-- row at a supporter's user would create a self-link that this guard never
-- re-checks -- it reads the two user_ids when the LINK is written, not when a
-- student is. Freezing that column belongs to `students`, not to this
-- migration, and no path in the product re-points it today.

CREATE OR REPLACE FUNCTION log_supporter_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO supporters_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, user_id
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.user_id
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_supporter_student_link_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO supporter_student_links_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, supporter_id, student_id, relationship
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.supporter_id, NEW.student_id,
        NEW.relationship
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Triggers
--
-- BEFORE triggers fire in trigger-name order, intended-subjects.sql's order
-- verbatim: 00 delete-guard, 00a shared immutable-guard, 00b row_created_at
-- guard, 00c this table's column guard, 01 versioning, 03 updated_at; then 04
-- AFTER INSERT OR UPDATE snapshot. The link table adds two, after its column
-- guard and before versioning: 00d no self-link (INSERT OR UPDATE) and 00e no
-- closed endpoint (INSERT only). Two rules, two functions, two triggers -- the
-- events they fire on differ, so one trigger could not state both.
-- ---------------------------------------------------------------------------

CREATE TRIGGER trigger_00_prevent_supporters_physical_delete
BEFORE DELETE ON supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_supporters_immutable_updates
BEFORE UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_supporters_column_updates
BEFORE UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_immutable_updates();

CREATE TRIGGER trigger_01_enforce_supporters_versioning
BEFORE INSERT OR UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_supporters_updated_at
BEFORE UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_supporter_version
AFTER INSERT OR UPDATE ON supporters
FOR EACH ROW EXECUTE PROCEDURE log_supporter_version();

CREATE TRIGGER trigger_00_prevent_supporter_student_links_physical_delete
BEFORE DELETE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_supporter_student_links_immutable_updates
BEFORE UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_supporter_student_links_column_updates
BEFORE UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_student_link_immutable_updates();

CREATE TRIGGER trigger_00d_prevent_supporter_student_links_self_link
BEFORE INSERT OR UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_student_link_self_link();

CREATE TRIGGER trigger_00e_prevent_supporter_student_links_closed_endpoint
BEFORE INSERT ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE prevent_supporter_student_link_closed_endpoint();

CREATE TRIGGER trigger_01_enforce_supporter_student_links_versioning
BEFORE INSERT OR UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_supporter_student_links_updated_at
BEFORE UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_supporter_student_link_version
AFTER INSERT OR UPDATE ON supporter_student_links
FOR EACH ROW EXECUTE PROCEDURE log_supporter_student_link_version();
