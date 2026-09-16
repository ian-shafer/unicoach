# RFC 221: supporters and supporter_student_links — the family link spine

Product slice `family/01/the-link`, brief 0014 (parents in the room). The
relationship itself: the adult side as its own entity, and a role-typed
many-to-many link from it to a student. Nothing user-facing lands here. This is
the spine every later slice in the brief authorises against, and it is its own
slice because getting the shape wrong is the one mistake the brief cannot
cheaply undo.

**Reachable by nobody, on purpose.** There is no API, no tool, no screen and no
copy in this RFC. That is precisely why it is not the first beat of the brief:
`family/02/invite-and-accept` opens the door, and a link row is only ever
created by accepting an invite (D2). Nothing here mints one.

## Decisions this slice inherits, and the two that changed at the gate

Inherited:

- **D1/D2 (brief 0014)** — a link exists only after an invite from one side and
  an explicit accept from the other, in both directions; either side unlinks at
  any time; unlink never deletes anyone's account or data; re-invite and
  re-accept recreates the link. No silent linking, ever.
- **D10 (brief 0001, standing)** — this slice adds tables, so the DDL was
  presented explicitly at the approval gate. It was, and it changed there.

Changed at the gate (Ian, 2026-09-15). Both are recorded as new numbered
decisions in brief 0014 at this run's close-out; the approved slice text is not
rewritten:

- **The adult side is its own entity, and the link references IT, never a user
  account.** The slice text and D6 described a single table carrying a parent's
  `user_id`. It is two tables: `supporters`, one row per adult user, and
  `supporter_student_links`.
- **The vocabulary is neutral, and the relationship word is honest.** D6 named
  `parent` as the role column's only value. The entity is a SUPPORTER, and the
  relationship carries four values: `parent`, `guardian`, `grandparent`,
  `supporter`. `supporter` is the catch-all, deliberately not `other` — a person
  who supports a student is described by what they do, not by failing to match
  the first three.

## Detailed Design

### Why the adult side is an entity

`students` (0005) is already a row per user rather than a flag on `users`, and
the adult side earns the same treatment for the same reasons.

It is a home for supporter-owned state that must not sit on `users`: the
parent's own coach scope (`family/06/the-parent-coach`), and the meter and
billing answer (`family/03.5/who-pays`). It also settles a question
`family/03/the-parent-account` leaves open — "whether parent-ness is a column, a
derived property of the link table, or a client-side state". It is none of
those: it is one row, found by `user_id` in one indexed lookup, and it is true
of an adult who has signed up and not yet linked anybody.

A link that pointed at `users` directly would have to be migrated the moment
that state arrived, with live rows in the table.

### `supporters`

One row per user, `students` verbatim in composition: `uuidv7` primary key,
`version`, the four timestamps, `deleted_at`, and a **total** unique index on
`user_id`.

Total, not partial, and this is the opposite choice from the link table below.
`students_user_id_unique_idx` states the reason and it holds here unchanged:
account deletion cascades the user, so there is no legitimate path that
re-creates a supporter for the same user, and a partial index would invite one.

`supporters` carries a `supporters_versions` history table and the full
`students` trigger set (Ian's call at the gate). Its only business column is
`user_id` and that column is frozen, so the history it keeps today is the
lifecycle — created, soft-deleted — and the point of it is the columns this
table WILL gain: a supporter's own settings, coach scope and billing state all
land here, and a history table added after those columns exist starts its record
at the migration rather than at the row.

### `supporter_student_links`

Many-to-many falls out of the shape: nothing restricts a `student_id` to one
row, and nothing restricts a `supporter_id` to one row. The only uniqueness is
the ACTIVE pair.

**The partial index is the load-bearing decision.** Unlink is a soft delete
(`deleted_at`) and re-linking must succeed, so the uniqueness index is partial —
`WHERE deleted_at IS NULL`. A total index would make "re-create the link at any
time" impossible, which D2 requires explicitly, and it is the single hardest
thing to change once rows exist. A re-link is a NEW row with its own
`created_at`, so the severed period stays visible in the table instead of being
erased by resurrecting an old row. This is
`college_list_entries_student_college_active_idx` (0024) in intent, and
deliberately not `students_user_id_unique_idx`.

`relationship` is **not** part of the uniqueness key. It describes the person;
it does not make a second link. A mother recorded as `parent` and then corrected
to `guardian` is one relationship throughout, and a key that included the word
would let the correction exist twice.

### DDL

```sql
-- ---------------------------------------------------------------------------
-- supporters -- the adult side, as its own entity.
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
```

Both tables carry a `_versions` history table of the same columns with
`PRIMARY KEY (id, version)`, an `(id, updated_at)` index, and a
`REFERENCES
... ON DELETE RESTRICT` back to the parent — `students_versions` and
`intended_subjects_versions` verbatim.

### Versioning and history

`version` plus `enforce_versioning()` gives optimistic concurrency control: two
sessions racing to unlink the same link cannot both win silently, and an UPDATE
that fails to set `version = version + 1` is refused with SQLSTATE 40001.

Both tables get a history table. For `supporters` that is Ian's call at the
gate, with the forward-looking reason given above. For `supporter_student_links`
it follows from `relationship` being **correctable**: that column is the one
business column here that legitimately changes in place, and "she was recorded
as a parent until March" is exactly the kind of statement a history table exists
to answer. An earlier draft of this RFC argued against a history table on the
grounds that no business column could change; the four-value vocabulary approved
at the gate removed that premise.

### What is immutable, and what is not

- `supporters.user_id` — frozen. Re-pointing a supporter row at another user
  would reassign every link that row holds, silently.
- `supporter_student_links.supporter_id`, `.student_id` — frozen. Re-pointing a
  link would make "this supporter has had access since March" a statement about
  somebody else. A different pair is a different link.
- `supporter_student_links.relationship` — **mutable**, and the only mutable
  business column in this migration. A correction is a correction, not a new
  relationship, and the history table records the old word.

Each table defines its own guard function, exactly as 0023, 0024 and
`intended-subjects.sql` define theirs, because the shared
`prevent_immutable_updates()` guards `id` and `created_at` only and cannot be
given column names.

### Both endpoints: not the same person, and both open

Two rules, and therefore **two functions and two triggers**. They do not fire on
the same events, so one function could only state both by testing `TG_OP` inside
its own body — which hides the event rule from every reader who does not open
it.

A student is not their own supporter (`trigger_00d`, `BEFORE INSERT OR UPDATE`).
The rule spans two tables (`supporters.user_id` against `students.user_id`), so
a CHECK cannot express it. It is enforced HERE rather than in
`family/02/invite-and-accept` because a constraint that lives in the only path
which writes today is a constraint the second writer will not have.

A link may not be made to a CLOSED endpoint (`trigger_00e`, `BEFORE INSERT`). A
foreign key proves that a supporter row and a student row EXIST; it says nothing
about whether either is still live, and both tables soft-delete. Without the
check, a link to an account closed years ago is a representable state — and
these rows are what every later slice of the brief authorises against. An
endpoint that is ABSENT is not this guard's business; the foreign keys refuse
that, under their own names.

Each endpoint is tested with its own `IF EXISTS (...)`. The shape this replaced
— `SELECT deleted_at IS NOT NULL INTO closed`, then `IF closed` — read a
THREE-valued result as two: an absent endpoint row leaves `closed` NULL,
`IF
NULL` takes the OPEN branch, and the guard admitted exactly the row it knew
least about. `EXISTS` is a boolean by construction, and the self-link probe asks
`IF FOUND` rather than comparing a fetched value against NULL.

**Every refusal names its own CONSTRAINT.** All three are `P0001`, the state any
PL/pgSQL `RAISE` produces, so the SQLSTATE cannot tell them apart and a caller
was left substring-matching English.
`RAISE ... USING ERRCODE = 'P0001',
CONSTRAINT = '<name>'` populates the error's
constraint field — the same field a unique index or a CHECK fills, and the field
the DAO mappers already route on — so the three refusals are
`supporter_student_links_not_self_link`,
`supporter_student_links_supporter_live` and
`supporter_student_links_student_live`, and each maps to its own typed
exception. The names end in neither `_check` nor `_idx` deliberately: no
catalogue row carries them, and the trigger is the object. Each message names
BOTH the supporter id and the student id, because which endpoint is closed is
useless without knowing which link was being minted.

**The liveness half fires on INSERT only, and that is load-bearing.** On UPDATE
it would refuse the unlink of a link whose student has since closed — the one
write that row still needs — and the row could never be cleaned up. Minting a
link to a closed account is the mistake; tidying one away is not. The self-link
half stays on `INSERT OR UPDATE`.

One known limit, recorded in the migration rather than fixed here:
`students.user_id` is not frozen, so re-pointing a student row at a supporter's
user would create a self-link this guard never re-checks — it reads the two
`user_id`s when the LINK is written. Freezing that column belongs to `students`,
and no path re-points it today.

### Triggers

House name order, on both tables: `00` physical-delete guard, `00a` shared
immutable guard, `00b` physical-timestamp guard, `00c` this table's column
guard, `01` versioning, `03` `updated_at`, `04` history snapshot. The link table
adds two after its column guard and before versioning: `00d` no self-link
(`INSERT OR UPDATE`) and `00e` no closed endpoint (`INSERT` only).

### Kotlin

In `:db` only. Nothing above that module changes.

- `SupporterId`, `SupporterStudentLinkId` — `@JvmInline value class ... : Id`,
  per `models/Entity.kt`.
- `SupporterRelationship` —
  `enum class SupporterRelationship(val value: String)` with `PARENT("parent")`,
  `GUARDIAN("guardian")`, `GRANDPARENT("grandparent")`, `SUPPORTER("supporter")`
  and a `fromValue` companion, per the schema convention in `CLAUDE.md`: an own
  enumeration is TEXT + CHECK plus exactly ONE Kotlin enum with `fromValue`.
- `Supporter`, `NewSupporter`, `SupporterStudentLink`, `NewSupporterStudentLink`
  — row models and insert inputs, `Identifiable`/`Created` per
  `models/Entity.kt`.
- `SupportersDao` — `create`, `findById`, `findByUserId`, `delete`. The two
  reads take a `SoftDeleteScope` (`db/models/SoftDeleteScope.kt`, the house
  primitive) rather than spelling the live/all split into two method names: a
  closed supporter is out of every default read and still resolves for a caller
  that asks with `ALL`. The scope rides in the WHERE clause as
  `scope.predicate()`, which is the one place that knows what each scope means —
  filtered in Kotlin after the read it would be a second copy of those three
  rules. `findById`'s refusal names the id AND the scope, so "no such supporter"
  and "a supporter this read was not allowed to see" are not one bare absence.
  `delete` is the compare-and-swap soft delete, the house verb for that write;
  the link DAO's `unlink` is the same shape under the product's own word for
  severing a relationship — a table that ships a soft-delete state ships the one
  sanctioned way to reach it, or the first caller that needs it writes its own
  UPDATE and rediscovers `version = version + 1`. A second supporter for the
  same user is `SupporterAlreadyExistsException` (the
  `StudentAlreadyExistsException` precedent), not an index name the caller has
  to recognise.
- `SupporterStudentLinksDao` — `create`, `findActive(supporterId, studentId)`,
  `listForStudent`, `listForSupporter`, `updateRelationship`, `unlink`. The last
  two are compare-and-swap UPDATEs that set `version = version + 1` and require
  the caller's version, and BOTH additionally require `deleted_at IS NULL`: a
  second unlink does not re-sever, and a correction applies to a LIVE link only
  — a severed link is history, and a re-link is a new row that takes the correct
  word at insert. The two side reads are one private query whose filter column
  is owned by a sealed `LinkSide` that only a typed `SupporterId` or `StudentId`
  can construct, so pairing `student_id` with a supporter's id is not something
  a call site can express.

**The three compare-and-swap writes fail in two distinguishable ways, not one
null.** `delete`, `unlink` and `updateRelationship` return `Result<T>` and go
through the house `occUpdate` primitive: zero rows updated is probed, and the
answer is `NotFoundException` when no row has that id at all and
`ConcurrentModificationException` when the row IS there and this write did not
apply. On the authorisation spine a wrong id and a lost race are different
mistakes with different fixes, and a shared null told them apart for nobody. The
second case covers both a stale version and an already-severed (or
already-closed) row, and that is honest: severing bumps the version, so whoever
won made this caller's version stale.

**Foreign-key refusals name the id that was missing** —
`No supporter [<id>] to
link`, not a constant sentence — which is the rule
`mapReferenceWriteError` already states and the reason the mappers are built
from the write's input.

Both DAOs follow `CostReportSharesDao`: a Kotlin `object` over `SqlSession`,
every method returning `Result<T>`, plain JDBC. Absence is null through
`orNullOnNotFound()` only where absence is ORDINARY — `findByUserId`,
`findActive` — and never for a write. A DAO is not API surface; it is the
spine's only sanctioned writer, and `family/02` is its first caller.

**Closing a supporter does not sever its links.** The rows stay exactly as they
are, and because the liveness guard fires on INSERT only each of them can still
be unlinked. What SHOULD happen to a closed supporter's links is an
account-deletion question and belongs to `family/07`; a cascade invented here
would answer it silently, in a migration nobody would think to read.

## Files Modified

New:

- `db/schema/supporters-and-student-links.sql` — both tables, both history
  tables, the guards and the triggers. No numeric prefix (RFC 180).
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/Supporter.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewSupporter.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterStudentLinkId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterStudentLink.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewSupporterStudentLink.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterRelationship.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SupportersDao.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDao.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SupportersDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDaoTest.kt`
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/IndexCatalogueFixture.kt` — the
  `pg_index.indpred` reading both test classes make. Written twice and
  byte-identical, over two deliberately OPPOSITE halves of one fact, so the two
  readings must not be able to differ.

Modified:

- `db/schema/ORDER` — one appended line (positioned at land by `ship-order`).
- `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt` —
  `SupporterAlreadyExistsException`, the three endpoint-guard types
  (`SelfSupporterLinkException`, `ClosedSupporterLinkException`,
  `ClosedStudentLinkException`), and the `P0001` SQLSTATE constant.

Nothing outside the `:db` module.

## Implementation Plan

1. Write `db/schema/supporters-and-student-links.sql`: two tables, two history
   tables, four own functions (a column guard each, two snapshot loggers), the
   endpoint guard, and the triggers in name order. Append the filename to
   `db/schema/ORDER`.
2. `nix develop -c bin/db-reset` — prove the migration applies from scratch.
3. Kotlin ids, the relationship enum, row models, insert inputs.
4. `SupportersDao`, then `SupporterStudentLinksDao`, on the
   `CostReportSharesDao` pattern.
5. The two test classes.
6. `nix develop -c bin/test`, and emit the suite record into the run scratch.

## Tests

Two classes on the `CostReportSharesDaoTest` pattern (raw `DriverManager`
connection, `TRUNCATE ... CASCADE` per test, `StudentRowFixture` for students, a
hand-written `users` insert). No API test, no route test, no snapshot: this
slice has no door.

The `pg_index.indpred` reading both classes need is `IndexCatalogueFixture` in
`:db`'s test fixtures, not a private copy in each class: the two assertions are
opposite halves of one fact, so the READING must be identical by construction.

`SupportersDaoTest`:

- A supporter is created for a user and found by `user_id`.
- **A second supporter for the same user is refused** — as
  `SupporterAlreadyExistsException`, whose message names the user, and the
  unique index is TOTAL, so it is refused even when the first row is
  soft-deleted. That second half is the assertion that would pass against a
  partial index, and it is the difference from the link table.
- `user_id` is immutable — an UPDATE raises `P0001`.
- An UPDATE that does not bump `version` is refused — `40001`.
- A physical DELETE is refused — `P0001`.
- A soft delete writes a `supporters_versions` row carrying `deleted_at`, so the
  removal itself is in the history and not merely the last state before it. The
  soft delete is `SupportersDao.delete`, never a hand-written UPDATE: a test
  that reached around the sanctioned writer is what the next caller would copy.
- **A second `delete` and a stale version both lose the race** —
  `ConcurrentModificationException` for each, and the first delete's
  `deleted_at` stands, because a re-stamp would move the moment the supporter
  left. **Closing an id no supporter has is `NotFoundException`** — its own
  test, because a wrong id on the authorisation spine must not look like a lost
  race.
- **A closed supporter is found only when the scope asks for it** — absent from
  `findByUserId` and from `findById`'s default `ACTIVE` scope, and resolving
  with its `deleted_at` under `SoftDeleteScope.ALL`. The refusal's message is
  asserted whole: it names the id and the scope that excluded it.
- **A supporter for a user that is not there reports the NAMED foreign key** —
  `supporters_user_id_fkey`, forced, with the absent user's id in the message.
  The DAO maps that Postgres-generated name as a string literal, and a literal
  no constraint matches is a branch that can never run.
- **`supporters_user_id_unique_idx` is TOTAL, asserted against the catalogue** —
  `pg_index.indpred` is null for it. It lives in this class because this class
  owns the table; the link table's partial half is asserted there.

`SupporterStudentLinksDaoTest`:

- **Many-to-many, both directions, proven by behaviour** — one student with two
  supporters, one supporter with two students; `listForStudent` and
  `listForSupporter` each return exactly the expected rows, and neither returns
  a severed one. Not a schema reading.
- **Unlink then re-link succeeds** — create, unlink, create again for the same
  pair. This test FAILS against a total unique index, which is exactly what it
  is for; it asserts two rows with different ids and that only the new one is
  active.
- **A second ACTIVE link for the same pair is refused** —
  `ConstraintViolationException` naming
  `supporter_student_links_supporter_student_active_idx`.
- **The index is partial, asserted against the catalogue** — `pg_index.indpred`
  is not null for it, mirroring `CostReportSharesDaoTest`'s
  `pg_constraint.confdeltype` assertion. The behavioural tests prove it works;
  this one proves WHY, so a later "consistency" change fails loudly. The
  opposite half — that `supporters_user_id_unique_idx` is TOTAL — is asserted in
  `SupportersDaoTest`, because a failure should name the table that regressed.
- **Two links for the same pair differing only in `relationship` are refused** —
  the word is not part of identity.
- **A relationship is corrected in place** — `parent` to `grandparent` on the
  same row id, `version` incremented, and `supporter_student_links_versions`
  holds both words in order.
- **A stale version loses on `updateRelationship`**, and — its own test — **a
  stale version loses on `unlink`**. One rule per test: each fails with
  `ConcurrentModificationException` and changes nothing. **An id no link has is
  `NotFoundException` on both**, in its own test, so the wrong-id case and the
  lost-race case are proven distinguishable.
- **A second `unlink` loses the race** — `ConcurrentModificationException`, and
  `deleted_at` is not re-stamped.
- **`updateRelationship` refuses a severed link** —
  `ConcurrentModificationException` (the row is there), and the severed row's
  word and `version` are unchanged. The past is not rewritten; a re-link takes
  the correct word at insert.
- **An UPDATE that does not bump `version` is refused** — `40001`, raised by
  this table's own `enforce_versioning` trigger and not by the DAO's `WHERE`.
- **A physical DELETE is refused** — `P0001`, and the row is still there.
- **A self-link is refused** — a supporter whose `user_id` is the student's own
  `user_id` fails as `SelfSupporterLinkException`, asserted by CONSTRAINT name
  (`supporter_student_links_not_self_link`) and not by a message fragment, with
  both endpoint ids in the message.
- **`supporter_id` and `student_id` are immutable** — an UPDATE of either raises
  `P0001`.
- **All four relationship words are accepted; a fifth is refused** — `23514`
  naming `supporter_student_links_relationship_check`.
- **The enum matches the constraint, both ways** — the CHECK's own definition is
  read back with `pg_get_constraintdef` and compared, WHOLE, against the
  definition rendered from `SupporterRelationship.entries`. Walking the enum
  alone closes one direction only: a word added to the constraint with no enum
  member would pass such a test untouched, and `fromValue` would then throw on a
  row the database was happy to store. Comparing the whole string rather than a
  set of words scraped out of it closes the rest: an extra OR-arm, or a
  predicate over another column, fails too — and the test holds no hand-rolled
  parser of SQL string literals, which is not a thing a test should own.
  `fromValue` returning null for an unknown word is its own test.
- **A link to a supporter that is not there, and a link to a student that is not
  there, each report the NAMED foreign key** — forced violations of
  `supporter_student_links_supporter_id_fkey` and
  `supporter_student_links_student_id_fkey`, asserting the constraint and the
  mapped message, which carries the absent id. Both names are Postgres-generated
  and mapped as string literals; unforced, either could name nothing at all.
- **A link to a CLOSED supporter is refused, and a link to a CLOSED student is
  refused** — `ClosedSupporterLinkException` and `ClosedStudentLinkException`
  from the `00e` guard, each asserted by its own CONSTRAINT name and carrying
  both endpoint ids. A foreign key proves existence, not liveness. The student
  is closed through `StudentsDao.delete` at the version the row actually holds,
  never a literal `1`: that is another table's token, and a test that hard-codes
  it fails later for a reason unrelated to links.
- **A link whose student is closed can still be UNLINKED** — the test that holds
  the liveness check on INSERT only. Were it to fire on UPDATE, the one write
  such a row still needs would be refused and the row could never be cleaned up.
