-- subscriptions: the owner moves from a STUDENT to an ACCOUNT. RFC 244, product
-- slice family/03.5/who-pays (brief 0014).
--
-- WHY. Billing is per account, not per student (RFC 244's decision, which
-- supersedes brief 0014 D10): a parent's account must be able to hold its own
-- subscription, and 0042's `student_id NOT NULL REFERENCES students(id)` makes a
-- non-student-owned row unrepresentable. The column becomes
-- `user_id NOT NULL REFERENCES users(id)` on the live table AND on the history
-- table, and `log_subscription_version()` is restated because it names columns.
--
-- Every existing row keeps its meaning. A `subscriptions` row's student had
-- exactly one account (`students.user_id NOT NULL`, `students_user_id_unique_idx`
-- TOTAL), so the backfill is a total function: a subscribed student's account now
-- holds the row their student profile held, and the gate reads it by account
-- (`SubscriptionsDao.findCurrentForUser`, resolved from the student one indexed
-- hop away) for exactly the same answer.
--
-- NO `ON DELETE` ACTION, deliberately -- exactly as `students.user_id` (0005)
-- and `supporters.user_id` (supporters-and-student-links.sql) take none. `users`
-- carries `trigger_00_prevent_physical_delete` (0001), so a cascade could never
-- fire and would only state a deletion policy this table does not own. The
-- `ON DELETE CASCADE` that leaves with `student_id` was in the same position:
-- `students` carries the same delete guard (0005), so it never fired either.
--
-- THE GUARD HAZARD, named because the backfill runs on a POPULATED table (the
-- test harness migrates a database that already holds subscriptions rows). Five
-- BEFORE triggers and one AFTER trigger are live on `subscriptions` (0042):
--
--   trigger_00a_prevent_subscriptions_immutable_updates  -- id/created_at: PASSES,
--       the backfill changes neither, and it stays attached on purpose.
--   trigger_00b_prevent_physical_timestamp_update        -- row_created_at: PASSES,
--       same reason, and it stays attached too.
--   trigger_01_enforce_subscriptions_versioning          -- REFUSES: an UPDATE
--       whose `version` is not OLD.version + 1 raises 40001.
--   trigger_03_enforce_subscriptions_updated_at          -- would stamp
--       `updated_at = NOW()` on every row.
--   trigger_04_log_subscription_version                 -- would INSERT a history
--       row at the SAME (id, version) and violate the versions PK.
--
-- The RFC 176 / 0094 precedent is to state which guard is dropped, do the work,
-- and re-create it, so the last three are dropped for the backfill and
-- re-created below, VERBATIM from 0042. Bumping `version` instead would have
-- satisfied all three -- and would have written a version row claiming the
-- subscription CHANGED at this migration. It did not: adding a derived column is
-- not an event in an entitlement dispute's timeline, which is the only thing
-- this history exists to answer. The two guards that pass are not touched, so a
-- post-migration UPDATE still refuses an immutable-column change and still
-- writes history -- asserted by test.

-- ---------------------------------------------------------------------------
-- Part 1 -- the new column, nullable, on both tables.
-- ---------------------------------------------------------------------------

ALTER TABLE subscriptions ADD COLUMN user_id UUID NULL REFERENCES users(id);

-- The history table carries no constraints of its own (0042's comment: it
-- records what was written), so no foreign key here -- the 0094 rule verbatim.
ALTER TABLE subscriptions_versions ADD COLUMN user_id UUID NULL;

-- ---------------------------------------------------------------------------
-- Part 2 -- the backfill.
-- ---------------------------------------------------------------------------

-- History first, and with no trigger dance: `subscriptions_versions` carries no
-- triggers at all (0042 attaches none to it).
UPDATE subscriptions_versions v
   SET user_id = st.user_id
  FROM students st
 WHERE st.id = v.student_id;

DROP TRIGGER trigger_01_enforce_subscriptions_versioning ON subscriptions;
DROP TRIGGER trigger_03_enforce_subscriptions_updated_at ON subscriptions;
DROP TRIGGER trigger_04_log_subscription_version ON subscriptions;

UPDATE subscriptions s
   SET user_id = st.user_id
  FROM students st
 WHERE st.id = s.student_id;

-- Re-created VERBATIM from 0042. trigger_04 waits until the writer it calls has
-- been restated (Part 4) -- a trigger is bound to the function by name, so the
-- order of these two statements does not matter to Postgres, but a reader
-- following the file should not see the history writer attached while its body
-- still names a column that is about to go.
CREATE TRIGGER trigger_01_enforce_subscriptions_versioning
BEFORE INSERT OR UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();
CREATE TRIGGER trigger_03_enforce_subscriptions_updated_at
BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

-- ---------------------------------------------------------------------------
-- Part 3 -- the column is now total.
-- ---------------------------------------------------------------------------
--
-- SET NOT NULL scans the table and refuses if the backfill missed a row, which
-- is the assertion this migration wants: `student_id` was NOT NULL with a real
-- foreign key to `students`, and `students.user_id` is NOT NULL, so a miss is
-- impossible and a failure here would mean the corpus disagrees with itself.
ALTER TABLE subscriptions ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE subscriptions_versions ALTER COLUMN user_id SET NOT NULL;

-- ---------------------------------------------------------------------------
-- Part 4 -- restate the history writer, student_id -> user_id.
-- ---------------------------------------------------------------------------
--
-- BEFORE the DROP COLUMN, the 0094 rule: a plpgsql body is stored as TEXT and is
-- not dependency-tracked, so DROP COLUMN neither fails nor rewrites it -- a
-- stale body survives the migration and then kills the very next write to
-- `subscriptions` with `42703 record "new" has no field "student_id"`, from
-- inside the trigger, in the middle of a purchase verification.
--
-- CREATE OR REPLACE, never DROP/CREATE: the 0042 body with one name changed on
-- each side of the VALUES.
CREATE OR REPLACE FUNCTION log_subscription_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO subscriptions_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        user_id, original_transaction_id, product_id, status, period_start, period_end
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at, NEW.row_updated_at,
        NEW.user_id, NEW.original_transaction_id, NEW.product_id, NEW.status, NEW.period_start, NEW.period_end
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_04_log_subscription_version
AFTER INSERT OR UPDATE ON subscriptions FOR EACH ROW EXECUTE PROCEDURE log_subscription_version();

-- ---------------------------------------------------------------------------
-- Part 5 -- the indexes, re-keyed.
-- ---------------------------------------------------------------------------
--
-- Both 0042 indexes lead on `student_id` and would be dropped WITH the column by
-- Postgres; they are named here so the swap is stated rather than inferred
-- (0094's Part 2 precedent). The partial one serves the gate's read --
-- SubscriptionsDao.findCurrentForUser -- and its predicate is 0042's verbatim,
-- because it is the same entitling status set.
--
-- That set is OWNED by SubscriptionStatus.entitles, which the DAO's read is
-- built from; this predicate is the one remaining copy of it, and
-- SubscriptionEntitlingStatusPinTest fails when the two disagree. Postgres
-- cannot catch that drift and its cost is silent: a status the enum entitles and
-- this predicate omits drops the gate's read onto a sequential scan.
--
-- A caller that starts from a student resolves the account through
-- `students_user_id_unique_idx` (0005) first, which that index already serves,
-- so no third index is added for it.
DROP INDEX subscriptions_student_current_idx;
DROP INDEX subscriptions_student_idx;

CREATE INDEX subscriptions_user_current_idx
  ON subscriptions (user_id, period_end) WHERE status IN ('active','grace');
CREATE INDEX subscriptions_user_idx ON subscriptions (user_id, created_at);

-- ---------------------------------------------------------------------------
-- Part 6 -- drop the student key from both tables.
-- ---------------------------------------------------------------------------
--
-- No CHECK, no view and no other trigger names `student_id` (0042 declares
-- neither on it, and the only views in the corpus are `student_llm_cost` and
-- this RFC's own two, none of which read `subscriptions`). The foreign key and
-- the `ON DELETE CASCADE` on it go with the column, as Postgres drops a
-- column's constraints (the 0007 / 0094 precedent); they are not dropped by hand.
ALTER TABLE subscriptions DROP COLUMN student_id;
ALTER TABLE subscriptions_versions DROP COLUMN student_id;

COMMENT ON COLUMN subscriptions.user_id IS
    'The ACCOUNT that owns this subscription (RFC 244). Until this migration the '
    'owner was students.id: billing was keyed to a student, so a supporter '
    'account could not hold a subscription at all. Every pre-migration row was '
    'mapped to its student''s account, which was a total function '
    '(students.user_id NOT NULL, one student per user). The student-keyed read '
    'is now a join through students.user_id. A second account signed in to the '
    'SAME Apple ID gets no row here and no coverage from this one: Apple will '
    'not sell the subscription twice, and RFC 244 counts that refusal rather '
    'than covering it.';
