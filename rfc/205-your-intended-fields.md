# RFC 205 — Your intended fields of study

**Slice:** `beyond/04/your-intended-fields` (brief 0011, "beyond money").
**Lane:** A (rfc) — new tables, a new REST surface, a new chat tool, new iOS UI.
**Base:** `main@e2bb9604`.

Let a student say what they want to study, and store it — as one of OUR 181
subjects (D38), never as a raw federal code. One answer, given once, that later
slices turn into what search shows (`beyond/08`), which peers we suggest, and
what the report can say about return (`beyond/05`, `beyond/07`). This slice
STORES. It changes no search behaviour and speaks no earnings figure.

## What the code says (measured at design time, 2026-09-14)

Four read-only research passes over `main@e2bb9604`; findings with `path:line`
citations in `.scratch/ship/rfc-205/research/`. The load-bearing facts:

| #   | The spec assumed                                                                       | The code/data says                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| --- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| R1  | "429 code/title pairs, 391 at bachelor's" — and that this is what a student picks from | **Two different lists, and the picker uses ours.** The FEDERAL list, measured on the dev database: `college_programs` holds 215,059 rows, **420** distinct 4-digit code/title pairs, **388** at bachelor's (`credential_level = 3`), a clean bijection. **OUR taxonomy**, `db/data/subjects.json` -> the `subjects` table: **181** family-worded subjects (counted from the file). D38 makes the 181 what a student answers with; the 420 stay the publisher's grain on college-side tables. **No count is hard-coded anywhere in this change.** |
| R1b | (nothing)                                                                              | `subjects.slug` is the `slug` DOMAIN primary key (`0064:48-52`), `name` is a free label, `cip_prefixes` is a `TEXT[]` of 2-, 4- or 6-digit prefixes with a shape CHECK. `neuroscience` = `2615, 3010, 422706`; `marine-biology` = `261302, 261304, 3029, 3032, 400607`. The taxonomy itself proves a single 4-digit code cannot carry a real answer.                                                                                                                                                                                             |
| R1c | (nothing)                                                                              | `CodebooksDao.deleteSubjectsNotIn` reloads with `DELETE FROM subjects WHERE slug <> ALL (...)`, and its doc comment states "nothing has a foreign key onto `subjects`". **This slice makes that comment false**, so this slice fixes it (D40).                                                                                                                                                                                                                                                                                                   |
| R2  | D30: "all four exhaustive `when`s"                                                     | There are **five** exhaustive `when`s over `AnswerStatus`, none with `else`, all in expression position: `ResidencyOffer.kt`, `CoachingService.kt`, `CollegeCostService.kt`, `SingleSchoolBasis.kt`, `ComparisonBasis.kt`. A fourth value is a hard compile error at all five. D30's intent holds; its count was wrong.                                                                                                                                                                                                                          |
| R3  | (nothing)                                                                              | `OpenApiMoneyProfileTest` asserts `AnswerStatus.entries` equals three OpenAPI enum lists **in declaration order**, and an iOS test fails if the Swift enum grows before the spec does. Growing the Kotlin enum is therefore also a wire-contract decision — see "The fourth status".                                                                                                                                                                                                                                                             |
| R4  | (nothing)                                                                              | **No count cap exists anywhere in the repo.** `college_list_entries` has no cap; the only cap-like thing is a read-side `MAX_COLLEGE_IDS = 50`. "Capped at 3" has no in-repo mechanism to copy, so this RFC chooses one.                                                                                                                                                                                                                                                                                                                         |
| R5  | (nothing)                                                                              | Tool-description byte ceilings were **all deleted by RFC 203**; the live tests carry "do not add a ceiling back" comments. A new tool ships **no** ceiling.                                                                                                                                                                                                                                                                                                                                                                                      |
| R6  | (nothing)                                                                              | No table stores a study answer for a student today; `students` has no array column and nothing references `subjects`. This is the first user reference to the taxonomy, which is what makes D40 this slice's problem.                                                                                                                                                                                                                                                                                                                            |
| R7  | (nothing)                                                                              | `college_search_index.cip_codes` holds **six**-digit codes from the completions census — a different vocabulary at a different width from the 4-digit code+title list. Bridging them is `beyond/08`'s problem, not this slice's.                                                                                                                                                                                                                                                                                                                 |
| R8  | (nothing)                                                                              | `DSPickerRow` is single-selection by construction (`Binding<Value>`, `.pickerStyle(.menu)`), and **no multi-select control exists anywhere in the iOS app**. The capped multi-select is new UI.                                                                                                                                                                                                                                                                                                                                                  |
| R9  | (nothing)                                                                              | Account deletion soft-deletes `students`, so `ON DELETE CASCADE` never fires. Nothing to add to a deletion enumeration — there is none.                                                                                                                                                                                                                                                                                                                                                                                                          |

## Detailed Design

### 1. What a stored answer points at: OUR subject, not a federal code (D38)

`db/data/subjects.json` already holds **181 family-worded subjects**, each
mapping to one or more federal code prefixes, loaded into the `subjects` table,
already used by search, and already named in the coach prompt ("search for it by
its subject word rather than by any code"). A student's answer references one of
those subjects.

A raw 4-digit federal code cannot represent the real answers, and the taxonomy
file proves it: `neuroscience` is `["2615","3010","422706"]` — three prefixes in
different families AND at different grains — while `marine-biology` is
`["261302","261304","3029","3032","400607"]`, FINER than the 4-digit grain the
earnings file publishes at. Storing one 4-digit code would have silently picked
one box for the student.

**Reference by `slug`, the existing primary key (D39).** `subjects` already
separates identity from label: `slug` is the `slug` DOMAIN primary key and is
never shown to anyone; `name` is the label and may change freely. A rename
therefore never touches a student's row.

### 2. Two tables, because there are two things (D36/D37, D41)

What a student wants to study is a **set of values**. How they answered the
question is an **answer status**. D37 forbids deforming either to save a
migration, and a zero-length set must not collapse three different answers into
one (D16): `unanswered`, `declined` and `undecided` are three answers.

Names are D41's: **`intended_subjects`** (many rows per student, capped at 3)
and **`intended_subject_status`** (one row per student). "Subject" is our word
across the table, the search index and the prompt; "field" is overloaded
(`CostField` alone appears 812 times) and "field of study" is mostly the name of
the federal FILE. The money profile is not widened — it is the MONEY profile,
and what a student wants to study is not money.

Both follow the proven `money_profiles` / `college_list_entries` composition
exactly: `uuidv7` primary key, `version`, the four timestamps, `deleted_at` soft
delete, a `*_versions` history table, and the shared triggers by name
(`prevent_physical_delete`, `prevent_immutable_updates`,
`prevent_physical_timestamp_update`, `enforce_versioning`, `update_timestamp`,
`log_*_version`). **No shared function is added or redefined**; the two
functions this migration defines in place (`log_intended_subject_version`, and
the column guard below) are its own, exactly as `0024` defines its own.

**DDL, presented in full at the approval gate (brief 0001 D10).** Migration
slug: `intended-subjects.sql` (no number — RFC 180; the apply order is one line
appended to `db/schema/ORDER` by `ship-order` at land).

```sql
-- Deprecation, not deletion (D40). A subject the taxonomy file no longer
-- carries is DEPRECATED once a student references it, and stays resolvable for
-- that student. A deprecated subject is offered to nobody and matches nothing.
--
-- A status column, not `retired_at` and not a boolean: a timestamp would claim
-- an event history this table cannot honestly keep (a subject can be dropped
-- from the file and later restored), and both a timestamp and a boolean are
-- two-valued. A status leaves room for a third state without a migration.
-- Spelled the way `college_list_entries.status` is spelled.
ALTER TABLE subjects ADD COLUMN status TEXT NOT NULL DEFAULT 'active';
ALTER TABLE subjects ADD CONSTRAINT subjects_status_check
    CHECK (status IN ('active','deprecated'));

-- The ANSWER: how this student answered "what do you want to study?".
CREATE TABLE intended_subject_status (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Four states, not three: "I don't know yet" is a real, common and useful
    -- answer at 16, and it must not read as silence (D16/D30). `status` beside
    -- `college_list_entries.status`, not `intended_subject_status_status`.
    status TEXT NOT NULL DEFAULT 'unanswered',

    CONSTRAINT intended_subject_status_status_check
        CHECK (status IN ('unanswered','answered','declined','undecided'))
);

CREATE UNIQUE INDEX intended_subject_status_student_active_idx
    ON intended_subject_status (student_id) WHERE deleted_at IS NULL;

-- The VALUES: an unordered set, at most 3, no ranking (D16).
CREATE TABLE intended_subjects (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    version        INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ NULL,

    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,

    -- Our taxonomy's own key (D38/D39). RESTRICT, like every other reference
    -- foreign key in this schema: a student's answer is never removed as a
    -- side effect of reloading reference data.
    subject_slug slug NOT NULL REFERENCES subjects(slug) ON DELETE RESTRICT
);

-- One ACTIVE row per (student, subject). Removing and re-adding later is a NEW
-- row with its own history, so a prior removal's history is never resurrected
-- into it — `college_list_entries_student_college_active_idx` verbatim.
CREATE UNIQUE INDEX intended_subjects_student_subject_active_idx
    ON intended_subjects (student_id, subject_slug) WHERE deleted_at IS NULL;
CREATE INDEX intended_subjects_student_idx
    ON intended_subjects (student_id) WHERE deleted_at IS NULL;
-- The lookup D40's deprecation decision needs: who depends on this slug?
--
-- Deliberately NOT partial on `deleted_at IS NULL`, unlike the two indexes
-- above. The foreign key onto `subjects(slug)` is RESTRICT and it does not care
-- about `deleted_at`: a student who added a subject and later REMOVED it still
-- holds the reference, so a DELETE of that slug still fails. The deprecation
-- decision must therefore count over ALL rows, live and soft-deleted, and a
-- partial index cannot answer the question it exists for.
CREATE INDEX intended_subjects_subject_idx
    ON intended_subjects (subject_slug);

-- ---------------------------------------------------------------------------
-- History: the full `college_list_entries` pattern, copied verbatim (0024)
--
-- A student's subjects are kept, not overwritten. "What was she planning in
-- March" must be answerable, so removal is a soft delete and every committed
-- state is snapshotted — INCLUDING `deleted_at`, so the history records the
-- removal itself and not merely the last row before it.
-- ---------------------------------------------------------------------------

CREATE TABLE intended_subjects_versions (
    id             UUID        NOT NULL REFERENCES intended_subjects(id) ON DELETE RESTRICT,
    version        INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    row_created_at TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    row_updated_at TIMESTAMPTZ NOT NULL,
    deleted_at     TIMESTAMPTZ NULL,
    student_id     UUID        NOT NULL,
    subject_slug   slug        NOT NULL,
    PRIMARY KEY (id, version)
);

CREATE INDEX intended_subjects_versions_id_updated_at_idx
    ON intended_subjects_versions (id, updated_at);

-- `intended_subject_status_versions` is the same table shape with `status`
-- in place of `subject_slug`, and the same five-plus-one trigger wiring.

-- This migration's own guards. `prevent_immutable_updates()` is SHARED and, as
-- redefined by 0023, guards `id` and `created_at` ONLY — it cannot be given
-- column names. Freezing a business column therefore needs this migration's
-- own function, defined in place exactly as 0023 and 0024 define theirs.
CREATE OR REPLACE FUNCTION prevent_intended_subject_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.subject_slug IS DISTINCT FROM OLD.subject_slug THEN
        RAISE EXCEPTION 'The subject_slug field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_intended_subject_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO intended_subjects_versions (
        id, version, created_at, row_created_at, updated_at, row_updated_at,
        deleted_at, student_id, subject_slug
    ) VALUES (
        NEW.id, NEW.version, NEW.created_at, NEW.row_created_at, NEW.updated_at,
        NEW.row_updated_at, NEW.deleted_at, NEW.student_id, NEW.subject_slug
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- BEFORE triggers fire in trigger-name order, 0024's order verbatim: 00
-- delete-guard, 00a shared immutable-guard, 00b row_created_at-guard, 01
-- versioning, 03 updated_at; then 04 AFTER INSERT OR UPDATE snapshot. 00c is
-- this table's extra column guard, after the shared one and before versioning.
CREATE TRIGGER trigger_00_prevent_intended_subjects_physical_delete
BEFORE DELETE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_delete();

CREATE TRIGGER trigger_00a_prevent_intended_subjects_immutable_updates
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_immutable_updates();

CREATE TRIGGER trigger_00b_prevent_physical_timestamp_update
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_physical_timestamp_update();

CREATE TRIGGER trigger_00c_prevent_intended_subjects_column_updates
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE prevent_intended_subject_immutable_updates();

CREATE TRIGGER trigger_01_enforce_intended_subjects_versioning
BEFORE INSERT OR UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE enforce_versioning();

CREATE TRIGGER trigger_03_enforce_intended_subjects_updated_at
BEFORE UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE update_timestamp();

CREATE TRIGGER trigger_04_log_intended_subject_version
AFTER INSERT OR UPDATE ON intended_subjects
FOR EACH ROW EXECUTE PROCEDURE log_intended_subject_version();
```

`intended_subject_status` takes the same shape: its own
`intended_subject_status_versions` table, its own
`log_intended_subject_status_version()`, the same trigger set in the same order,
and its own `student_id` guard. Both are copied from `0046` / `0024` with no
deviation.

**Two invariants the CHECKs cannot express.** Both are enforced as
`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`, judged at COMMIT:

- **cap** — a student has at most **3** active `intended_subjects` rows.
- **status iff subjects** — a student has at least one active row **exactly
  when** `status = 'answered'`. This is the cross-table translation of
  `money_profiles`' `*_value_iff_answered_check`: a declined, undecided or
  unanswered student can never smuggle a stale subject to a consumer, and an
  answered one is never empty.

Deferred is not a convenience, it is required (D41): mid-transaction the two
tables are legitimately inconsistent while a student swaps one subject for
another, and a CHECK cannot contain a subquery in any case.

The cap is ALSO checked in the service before the write, so a caller gets a 400
with a reason rather than a constraint violation; the trigger is the backstop a
second writer cannot get past. The write path takes the student's
`intended_subject_status` row `FOR UPDATE` first, so two concurrent writes
serialise instead of both reading "2 rows, room for one more".

### 2a. The history is the point, and it is `college_list_entries` verbatim

A student's plan changes. "What was she planning in March" has to be answerable,
so an `intended_subjects` row is never destroyed:

- a physical `DELETE` is refused by `prevent_physical_delete`;
- removing a subject is an `UPDATE` that sets `deleted_at`, which
  `enforce_versioning` counts as a version;
- an `AFTER INSERT OR UPDATE` trigger snapshots the whole row into
  `intended_subjects_versions` **including `deleted_at`**, so the history
  records the removal itself rather than stopping at the last row before it;
- the versions table references the live row `ON DELETE RESTRICT`, so the
  history cannot be orphaned;
- the live-uniqueness index is partial on `deleted_at IS NULL`, so re-adding a
  subject after removing it is a NEW row with its own history — never a
  resurrection of the old one, and never a row whose history says it was removed
  and then silently un-removed.

**`student_id` and `subject_slug` never change after insert.** This is the rule
that makes the history worth keeping. If a row could be updated from one subject
to another, "she was planning nursing in March" would be answered by a row that
is now a biology row, and the version history would record a subject swap that
never happened as a continuous plan. A student swaps a subject by removing one
row and inserting another. `deleted_at` (and the bookkeeping columns) are the
only things that ever change.

**One correction to how that is spelled.** The instruction for this change named
`prevent_immutable_updates`. The shared function of that name cannot do it: as
redefined by `0023`, it guards `id` and `created_at` only, takes no column
arguments, and is attached to eleven other tables — widening it would change
their behaviour. The code wins, so this migration keeps the shared trigger AND
adds its own `prevent_intended_subject_immutable_updates()`, attached as
`trigger_00c_...`, guarding `student_id` and `subject_slug` by name. `0023` and
`0024` both define their own trigger functions in place; this is the same move.
`intended_subject_status` gets the same treatment for `student_id` alone — its
`status` column is exactly what is meant to change.

### 2b. Deprecation, not deletion (D40) — this slice is the first user reference

`CodebooksDao.deleteSubjectsNotIn` — renamed `reconcileSubjectsNotIn` by this
slice, because it no longer only deletes — reloads the taxonomy with
`DELETE FROM subjects WHERE slug <> ALL (...)`, and its own doc comment says
"Unlike a codebook row, a dropped subject needs no reference check: nothing has
a foreign key onto `subjects`." **This slice makes that comment false**, so this
slice fixes it. Left alone, the first student answer would turn an edit to
`subjects.json` into an ingest that cannot run at all.

The reload becomes three outcomes rather than one:

- a dropped slug **nothing references** is DELETED, exactly as today — the
  reference tables stay reproducible from source;
- a dropped slug a student references is **DEPRECATED**:
  `status = 'deprecated'`, the row stays, and the student's answer stays
  resolvable to its name;
- a slug that reappears in the file goes back to `status = 'active'` by the
  ordinary upsert. That round trip is exactly why the column is a status and not
  a timestamp: a subject dropped in March and restored in May has no "retired
  at" moment worth recording, and pretending otherwise would be a fabricated
  event history.

A deprecated subject is offered to nobody: it is excluded from the picker, and
excluded from the search-index rebuild that computes
`college_search_index.subject_slugs`, so it stops matching colleges. It resolves
only for the student who already chose it.

And the raw-error path is closed too (D40): a foreign-key violation from this
table is reported with the **slug named and its dependants counted**, not as a
bare SQL state. It gets its OWN mapper, `mapSubjectDeleteError`, rather than
reusing `mapCodebookWriteError`: that one's message speaks about
`us_states.ipeds_region`, which cannot be what happened here, and the dependant
count must be captured BEFORE the write, since it is unrecoverable once the
transaction has aborted. The loader's report says how many subjects were deleted
and how many were deprecated.

### 2c. One subject, zero or several published figures — do not foreclose it

Because the student answers at OUR grain and the publisher publishes at theirs,
one stored subject resolves at a given school to **zero, one, or several**
published programmes. `neuroscience` spans three prefixes; `marine-biology` is
finer than the 4-digit grain the earnings file publishes at.

This slice speaks no figure, so it answers none of that. What it must not do is
model in a way that makes the honest answer impossible, so:

- the student's row stores the **slug and nothing else**. No code is frozen into
  it at write time, so a later slice resolves through `subjects.cip_prefixes` as
  the taxonomy stands then, not as it stood the day the student answered;
- nothing here aggregates, averages or picks a representative code.

For the later slices (`beyond/05`, `beyond/07`), stated here so it is not
rediscovered: each matching programme is named individually and **never averaged
across codes** — an average nobody published is the false figure D27 forbids —
and a subject finer than the published grain says so in words.

### 3. The write is one operation: replace the whole answer

There is no add-one and no remove-one endpoint, and **`Clear` is not exposed**
(D17). One operation takes the student's whole answer and one of four shapes:

| The student said                  | Stored                                                  |
| --------------------------------- | ------------------------------------------------------- |
| "nursing, and maybe biology"      | `answered` + 1..3 rows                                  |
| "I don't know yet"                | `undecided`, zero rows                                  |
| "I'd rather not say"              | `declined`, zero rows                                   |
| removes the last subject they had | **`undecided`**, zero rows — never back to `unanswered` |

The last row is D17, stated as a rule the code enforces: an empty set submitted
by a student who had subjects resolves to `undecided`. We do not re-arm an
invitation the family already answered. `unanswered` is only ever the state of a
student who was never asked, and no caller can set it.

### 4. The fourth status (D30)

`AnswerStatus` gains `UNDECIDED("undecided")`. That is deliberate: it makes all
five exhaustive `when`s fail to compile until each one states what it does with
it (R2). Each of the five renders a MONEY field, where `undecided` is
unreachable; each therefore gets an **explicit, named branch**, never a silent
fold into `declined`. "I don't know yet" and "I'd rather not say" are different
sentences and the code says so at every site.

The money fields cannot become undecided, and the database is what guarantees
it: `money_profiles`' three status CHECKs stay at
`('unanswered','answered','declined')`, untouched by this migration. A test
asserts the database refuses `undecided` on a money column.

That splits the wire contract from the Kotlin enum, and the split is honest
rather than accidental:

- the three money-profile OpenAPI enums keep **three** values, and
  `OpenApiMoneyProfileTest` is rewritten to assert they equal
  `AnswerStatus.entries` **minus** `UNDECIDED`, with the reason in the message;
- the new intended-subject schema lists **four**;
- the Swift `AnswerStatus` gains the fourth value and its test follows the same
  split.

A single `entries == spec` assertion would have forced `undecided` onto income
band, where it would be a value the database refuses — a spec that lies.

### 4a. A conflict with brief 0010, stated and not resolved here

Brief 0010 ("when do I test") has an unstarted slice
`test/06.1/one-answer-one-date`. It will create `DOMAIN answer_status AS TEXT` —
one definition of the answer vocabulary for the whole schema — and its written
acceptance criterion is: "One definition of each vocabulary in the schema. A new
table declaring its own copy of either fails review, and the domains are the
only way to spell them."

This slice lands FIRST, and it declares its own copy: a plain `TEXT` column with
`CHECK (status IN ('unanswered','answered','declined','undecided'))`.

**That is deliberate, and it is temporary.** The reason is the fourth value.
`undecided` is legal on an intended-subject answer and must NEVER be legal on a
money column — `money_profiles` keeps its three-value CHECKs, and a test asserts
the database refuses `undecided` there. A single `answer_status` domain over all
four values would silently make `undecided` insertable into every money status
column, which is the exact failure this slice is built to prevent. Adopting a
domain that does not exist yet is not an option either.

**/chart has decided, and the decision is recorded here rather than left open.**
The answer is **two domains, not one**: a three-value domain for a question
whose only answers are unanswered, answered or declined, and a four-value domain
for a question where "I don't know yet" is possible. The reason is that these
are two vocabularies, not one vocabulary with an optional extra: a single
four-value type would make `undecided` legal on an income band unless every
money column separately remembered to forbid it, and a rule that must be
remembered at every site is the rule that gets forgotten.

`test/06.1` owns that work and is told directly; this run neither resolves it
nor waits for it. What this slice guarantees is that the handover costs one
line: the CHECK here is a single NAMED constraint
(`intended_subject_status_status_check`), so retyping this column onto the
four-value domain is a drop-constraint plus an alter-type, with no data change.

### 5. `GET /api/v1/subjects?q=` — the picker's list (D29, renamed by D41)

D29 asked for a new endpoint rather than `/api/v1/vocabularies`, because RFC 165
excludes the subjects taxonomy from served vocabularies by rule and that
document is uncached, unpaged and refetched on every screen appearance. That
still holds. What changed is WHAT it serves: our 181 subjects, not the federal
file, so the route is named for the thing — `subjects` — which is also the word
D41 fixes for this taxonomy everywhere else.

It follows the `GET /api/v1/colleges` precedent: session required, no profile
gate (the same reasoning that route already states in a comment), and the shared
`validation_failed` + `FieldError` 400 body. Two deliberate divergences, stated
because they are divergences:

- **`q` is OPTIONAL here.** 181 rows is a browsable list, and a picker that
  shows nothing until the student types is a worse picker. Absent `q` serves the
  list in name order. A blank-but-present `q` is still a 400, as on the college
  route.
- **`limit` defaults to the whole list** and clamps 1..200, because the list is
  closed and small. The college route clamps 1..25 because its corpus is 6,273
  rows.

It serves `slug` and `name`, excluding deprecated subjects, matched
case-insensitively on `name`. The slug is identity and the client sends it back;
the name is the label (D39).

**No synonym is ever stored, and none is invented** (D15's surviving half). This
slice matches the published subject names only. A term that is not a subject is
refused **with a reason** — "that is not a subject on the list" — rather than
silently mapped to something near it. A curated synonym aid is a later decision,
and the RFC says so rather than leaving a student to discover it.

### 6. The two doors (D17)

**The screen.** `GET`/`PUT /api/v1/students/me/intended-subjects`, a sibling of
the money-profile route, not an extension of it (D37). It reuses that route's
caller-resolution ladder and its pooled-400 validation idiom. Request keys,
**exactly one** of: the set of slugs, `declined`, `undecided`, counted by KEY
PRESENCE and not by boolean truth — `{"subjects": [...], "declined": false}` is
two keys and is refused, because a client that sent `declined` at all has said
something about it. The two flags are therefore nullable on the wire and
published as `enum: [true]` with no `default`, so the spec cannot invite the
shape the route refuses. Both doors read the same rule from one place, so the
chat question and the screen agree by construction rather than by coincidence.
Exactly one, not at most one — the money-profile route can take a no-op write
because its keys are independent fields, but here the three keys are three
spellings of the SAME answer, so a body naming none of them states no answer and
is a malformed request rather than a write of nothing. There is no clear key.
Response: the status always; the slugs with their names when answered.

A 400, with a reason naming the offending input, for: more than 3 slugs; a slug
that is not in the taxonomy; a deprecated slug the student does not already
hold; a duplicate slug.

**The chat.** One new `StudentScopedChatTool`, registered in the single
`ToolRegistry(listOf(...))` in `Application.kt`. It can look subjects up and it
can record an answer, and it **cannot invent one**: it records only a subject it
resolved unambiguously against the taxonomy, and otherwise returns the
candidates and stores nothing. It accepts "I don't know yet" and "I'd rather not
say" as answers, and it says what the stored answer now buys. It ships **no byte
ceiling** (R5). Its result names subjects in words; no slug reaches the student.

**Value before ask (brief 0001 D12), and guided, not gated (D11).** One new
paragraph in `prompts/coach-system-prompt.md` — the file, and nothing else; the
seed and the version pin are generated at land by `bin/prompt-seed`. Authored
one sentence per line. It says: offer the question only when the answer would
change what you say; name what it buys before asking; "I don't know yet" is a
real answer to record, not a non-answer to push past; never record a subject the
student did not choose; the student may change or remove it at any time. The
paragraph is registered in `SystemPromptCatalogTest`'s `CoachParagraph` index,
which otherwise fails its own drift guard.

Nothing downstream is gated on having answered. Every later consumer degrades to
a labelled answer.

### 7. iOS — the capped multi-select that does not exist yet (R8)

A new design-system row: a labelled row that opens a searchable list, shows the
chosen subjects as up to three removable chips, and offers "I don't know yet"
and "Prefer not to say" as first-class choices beside them. `DSPickerRow` is not
extended — it is single-selection by construction and its own comment says why.

The list is fed by the new route through a thin client on the existing
`VocabularyClient` shape, using the `AddCollegeViewModel` debounce idiom. It
needs **no** shipped fallback list, because it is a search route rather than a
vocabulary: with the network down the row degrades to showing what is already
stored, and says the list is unavailable.

A decline or an "I don't know yet" set in chat is visible and undoable here, and
the reverse — that is the acceptance criterion "a decline is durable and
undoable from the screen".

### 8. Explicitly out of scope

- **Search.** Nothing is fed into the similarity SUBJECTS axis and `DEFAULTS` is
  untouched (C15/D22). The stored subject changes no search result in this
  slice; `beyond/08` is where a student's subject reaches search.
- **Figures.** No earnings, no debt, no payment, no `program_money_stats`. That
  is `beyond/03` and `beyond/05`.
- **Permission.** Nothing renders this to a parent, so there is no permission
  here (D35: `beyond/10` owns it, `beyond/07` consumes it). This slice stores
  the subjects and NOTHING else.
- **Converging the other reference tables.** D40's scope note: states, locales,
  Carnegie classes, religious affiliations and federal codes are referenced only
  by data rebuilt from source, so they have no identity-stability problem.
  `subjects` is the only one about to carry user references.

## Files Modified

**New**

- `db/schema/intended-subjects.sql` — `subjects.status` plus its CHECK, the two
  tables, two version tables, fourteen trigger wirings, two log functions, two
  column-immutability guard functions, two deferred constraint triggers. One
  line appended to `db/schema/ORDER` by `ship-order`.
- `db/src/main/kotlin/ed/unicoach/db/models/IntendedSubject.kt`,
  `IntendedSubjectAnswer.kt`.
- `db/src/main/kotlin/ed/unicoach/db/dao/IntendedSubjectsDao.kt`.
- `service/src/main/kotlin/ed/unicoach/coaching/IntendedSubjectsService.kt`,
  `SubjectVocabularyService.kt`, and the new chat tool.
- `rest-server/src/main/kotlin/.../IntendedSubjectsRoutes.kt`,
  `SubjectsRoutes.kt`, and their DTOs.
- iOS: a new multi-select component in `DesignSystem/Components.swift`, an
  intended-subjects client and a subjects-list client.

**Changed**

- `db/src/main/kotlin/ed/unicoach/db/models/AnswerStatus.kt` — the fourth value.
- The five exhaustive `when` sites (R2) — one explicit branch each.
- `db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt` —
  `deleteSubjectsNotIn` becomes `reconcileSubjectsNotIn`, delete-or-deprecate,
  its doc comment is corrected, and `subjects`/`subjectCount` exclude deprecated
  rows where the caller offers them; the foreign-key error path names the slug
  and its dependants.
- The `SubjectLoader` report — deleted vs deprecated counts.
- The search-index rebuild — deprecated subjects match no college.
- `rest-server/.../Application.kt` — one line in the tool registry, plus service
  construction.
- `api-specs/openapi.yaml` — the new routes and schemas; the money enums
  unchanged.
- `rest-server/src/test/.../OpenApiMoneyProfileTest.kt` — the entries-minus-one
  assertion and its reason.
- `prompts/coach-system-prompt.md` — one paragraph.
- `service/src/test/.../SystemPromptCatalogTest.kt` — the `CoachParagraph`
  entry.
- iOS `YourDetailsView.swift`, `YourDetailsViewModel.swift`, `Models.swift`,
  `AuthenticatedRootView.swift`.

**Never touched:** `.claude/**`, `money_profiles`' columns or CHECKs, the
similarity axis, `DEFAULTS`, `subjects.slug` values, the other reference tables.

## Implementation Plan

1. Migration: `subjects.status`, the two tables, version tables, triggers, the
   two deferred constraint triggers; `db-reset` clean.
2. `AnswerStatus.UNDECIDED`, then fix the five `when`s the compiler names.
   Compile first, decide each branch deliberately, no `else` added anywhere.
3. Models and DAO, including the `FOR UPDATE` replace-the-set write.
4. `CodebooksDao` delete-or-deprecate + the loader report + the search-index
   exclusion (D40) — before anything can reference a subject in anger.
5. The subjects list route (the picker door).
6. The intended-subjects route (the screen door).
7. The chat tool + registry line + the prompt paragraph + `CoachParagraph`.
8. OpenAPI, and the two spec-coupled tests.
9. iOS component, clients, screen wiring.

## Tests

**Schema.** The status CHECK accepts exactly the four values and rejects a
fifth. The foreign key refuses a slug that is not a subject. The partial unique
index refuses a duplicate active subject and permits re-adding a soft-deleted
one. Versioning: an update writes a history row; a physical delete is refused.

**The history (the `college_list_entries` pattern).** Removing a subject writes
a versions row whose `deleted_at` is set, so the removal itself is in the
history, not just the last state before it. Re-adding the same subject after
removal creates a NEW `id` with `version = 1`, and the removed row's history is
untouched. Deleting a live row physically is refused; deleting it while a
versions row points at it is refused by `ON DELETE RESTRICT`. A row's whole
history for a student is readable in one query, so "what was she planning on
date X" is answerable from the versions table alone.

**Immutability.** An `UPDATE` changing `student_id` is refused. An `UPDATE`
changing `subject_slug` is refused. An `UPDATE` setting `deleted_at` on the same
row SUCCEEDS — the guard freezes the identity columns and nothing else. Changing
`student_id` on the status table is refused; changing its `status` succeeds.

**The two deferred invariants.** Four rows in one transaction is refused at
COMMIT. `answered` with zero rows is refused; `declined`/`undecided`/
`unanswered` with one row is refused. A single transaction that swaps one
subject for another — legitimately inconsistent in the middle — SUCCEEDS; that
is what `DEFERRABLE` buys and the test says so. Two concurrent writers cannot
land four rows between them.

**Deprecation (D40).** Reloading a taxonomy file with a subject dropped: the
subject NOBODY references is deleted; the subject a student references is
deprecated, not deleted, and the ingest still succeeds. A deprecated subject
still resolves to its name for that student; it is absent from the picker; it
matches no college after a rebuild. A slug that reappears in the file goes back
to `active`, and a drop-then-restore cycle leaves the row `active` with no trace
of a state the taxonomy no longer claims. The status CHECK accepts exactly
`active` and `deprecated` and refuses a third value. A foreign-key violation
from this table reports the slug and its dependants, not a bare SQL state.

**Money is untouched.** The database refuses `undecided` on each of the three
money status columns. `OpenApiMoneyProfileTest` asserts the money enums are
`AnswerStatus.entries` minus `UNDECIDED`.

**The transitions.** Removing the last subject lands `undecided`, not
`unanswered`. No caller can write `unanswered`. Declining after answering drops
the rows. Re-answering after declining restores them.

**The subjects route.** Absent `q` serves the list in name order; blank `q` →
400; over-long `q` → 400 naming the observed length; `limit` non-integer → 400;
`limit` 0 and 9,999 are clamped, not rejected; deprecated subjects are absent;
no session → 401.

**The profile route.** Four slugs → 400; an unknown slug → 400 with the "not a
subject on the list" reason; a duplicate → 400; two of the three keys at once →
400 carrying ONE pooled error that names all three keys — the money route's
`count { it } > 1` idiom produces a single `FieldError`, not one per key, so the
test asserts `fieldErrors` has size 1; the round trip GET-after-PUT returns the
slugs with names.

**The chat tool.** An unambiguous subject name is recorded; an ambiguous or
unknown term records NOTHING and returns candidates with a reason; "I don't know
yet" is recorded as `undecided` and reported as an answer; the fourth subject is
refused with a reason. No slug appears in the tool's result text.

**The prompt.** The new paragraph is present and reachable in the composed
system prompt; the `CoachParagraph` drift guard passes; the previous pin still
rolls back (the pin is read from `service.conf` at test time, never literal).

**No count is asserted.** No test hard-codes 181, 420, 388 or any other size of
either list (R1) — the fixtures build their own taxonomy.

**iOS.** The new component's coverage for: empty, one chip, three chips (the add
affordance is gone), undecided, declined, and list-unavailable.

## Rollback

Full. Revert the code commit and the RFC commit; the migration is additive and
the two tables are read by nothing else. Dropping them leaves `subjects` with an
unused `subjects.status` column defaulting to `active`, which is inert. The
prompt paragraph rolls back by the pin that was live at design time, read from
`service.conf`. `AnswerStatus` losing its fourth value is a compile-time change,
not a data change: no money row ever held `undecided`, because the database
refused it.
