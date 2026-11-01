-- deleted_users, deleted_students, deleted_supporters: where a deleted account
-- is (RFC 264, slice deletion/02/the-deleted-tables, brief 0002 D1/D5/D9).
--
-- WHY. Gate-1 decision D1: a deleted account is a row here, and every key onto
-- a person gains a sibling key onto these tables (deleted-key-*.sql), exactly
-- one of the pair set. A row that is to outlive the person -- the
-- subscription, a parent's conversation about a student, a spend row -- keeps
-- the sibling once the person is gone. This migration adds the tables only;
-- deletion/03 (the walk) is the only writer of a row.
--
-- id IS the former account id (D9), so spend and history read under the same
-- id across erasure. It references the live root (RFC 264 D-C): D1 keeps the
-- users / students / supporters row (personal columns NULL, deleted_at set),
-- so the key is satisfiable, and a deleted row for an id that was never an
-- account cannot exist. user_id is UNIQUE, mirroring
-- students_user_id_unique_idx and supporters_user_id_unique_idx: one student
-- and one supporter per account, live or deleted.
--
-- No column is personal: D5 keeps a record that an account existed and when it
-- was deleted, and nothing else. None is pii.*, so drift rule 2 treats a key
-- onto these tables as not owned.
--
-- APPEND-ONLY. A deleted row is never changed or removed: the log-table guard
-- pair, prevent_log_update / prevent_log_delete
-- (0006.create-coaching-conversations.sql), under the log-table trigger names.
--
-- COUPLING. Every key made nullable by deleted-key-*.sql is read by a DAO row
-- mapper that assumes it is set (UUID.fromString(rs.getString(..)) in
-- StudentsDao, ConvosDao, SubscriptionsDao, ClaimsDao and the rest), and no
-- build or test fails when one meets a NULL. deletion/03, the first writer of a
-- sibling key, owns that: before it writes one, it makes those mappers total or
-- filters erased rows out of their queries.

CREATE TABLE deleted_users (
    id         UUID        NOT NULL PRIMARY KEY REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,               -- users.created_at: when the account was created
    closed_at  TIMESTAMPTZ NOT NULL,               -- users.deleted_at: when it was closed
    erased_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()  -- when the walk erased it
);

CREATE TABLE deleted_students (
    id        UUID        NOT NULL PRIMARY KEY REFERENCES students(id),
    user_id   UUID        NOT NULL UNIQUE REFERENCES deleted_users(id),
    erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE deleted_supporters (
    id        UUID        NOT NULL PRIMARY KEY REFERENCES supporters(id),
    user_id   UUID        NOT NULL UNIQUE REFERENCES deleted_users(id),
    erased_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trigger_00_prevent_deleted_users_update
BEFORE UPDATE ON deleted_users
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_deleted_users_delete
BEFORE DELETE ON deleted_users
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

CREATE TRIGGER trigger_00_prevent_deleted_students_update
BEFORE UPDATE ON deleted_students
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_deleted_students_delete
BEFORE DELETE ON deleted_students
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();

CREATE TRIGGER trigger_00_prevent_deleted_supporters_update
BEFORE UPDATE ON deleted_supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_log_update();

CREATE TRIGGER trigger_01_prevent_deleted_supporters_delete
BEFORE DELETE ON deleted_supporters
FOR EACH ROW EXECUTE PROCEDURE prevent_log_delete();
