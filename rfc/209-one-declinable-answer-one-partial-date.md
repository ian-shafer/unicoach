# RFC 209 — one declinable answer, one partial date

**Slice:** `test/06.1/one-answer-one-date` (brief 0010,
`product/0010-when-do-i-test/spec.md`). **Lane:** A (rfc). Schema change, two
new schema vocabularies, a data migration of `students`, and a refactor that
moves two seams. **No user-visible behaviour changes, and that is the point.**

`test/06/your-scores` was parked at its approval gate on 2026-09-14 because it
was about to write, for the fourth time, two shapes this repo already has: a
variable-precision date stored as three SMALLINTs plus three CHECKs, and a
declinable answer stored as
`TEXT + CHECK IN ('unanswered','answered',
'declined')` plus a
value-iff-answered CHECK. Ian: _"Let's stop duplicating this. We should refactor
to a single pattern / component."_ and _"We should have reusable components and
patterns for this data type."_ Brief 0010 D23 and D24 decide what the two
components become. This RFC builds them and migrates every existing copy, so
that the next table declares neither shape again.

Both shapes are **already shared in Kotlin** — `PartialDate` is a sealed
interface whose own KDoc says it is domain-agnostic for reuse by test dates, and
`AnswerStatus` is one enum with one decode site. It is only the SQL half that
was copied. That asymmetry is the whole of the defect, and it is why the fix is
small.

## Detailed Design

### 1. The declinable answer: two spellings, one shared word list

The vocabulary `('unanswered','answered','declined')` is written four times in
`db/schema` (`0046` twice, `0070`, `0078`), and the pairing rule
`(value IS NOT NULL) = (status = 'answered')` four more. The repo already states
the argument against that, in its own words, at
`0054.create-cds-admissions-tables.sql:97`:

> "The vocabulary is one value shared by every factor column, so it is declared
> ONCE as a DOMAIN over TEXT ... Adding or renaming a rating is one ALTER DOMAIN
> plus the Kotlin enum, not eighteen edits."

There are **two** vocabularies here, not one. A money question — income band,
state of residency, living plan, dependency — has three possible answers. The
question RFC 205 is landing, "what do you want to study?", has a fourth:
`undecided`, which at 16 is a real and durable answer and is not the same
sentence as "I'd rather not say". One four-value domain everywhere would make
`undecided` legal on an income band unless every money column carried a
narrowing CHECK of its own — a shared superset with four private corrections,
which is the duplication a domain exists to remove, wearing the domain's
clothes.

And two vocabularies rather than three-values-everywhere, because of the
direction of regret. **Cheap-to-reverse is only true when the information still
exists.** A four-state vocabulary can be collapsed later if the fourth state
turns out to be noise; a three-state one cannot be expanded to recover a
distinction it never stored. The collapse is the reversible direction, never the
expansion.

The narrow spelling is **derived from** the wide one rather than declared beside
it, so the three shared words exist exactly once:

```sql
CREATE DOMAIN answer_status_or_undecided AS TEXT
    CONSTRAINT answer_status_or_undecided_check
    CHECK (VALUE IN ('unanswered', 'answered', 'declined', 'undecided'));

CREATE DOMAIN answer_status AS answer_status_or_undecided
    CONSTRAINT answer_status_excludes_undecided
    CHECK (VALUE <> 'undecided');
```

"The money spelling is the subject spelling minus `undecided`" is then a fact
the database enforces, not a comment two migrations apart. **The inversion is
deliberate and worth naming:** the ordinary case, money, is the derived one,
over a base that includes a value it can never hold. That reads backwards, and
it buys the containment guarantee.

The names carry the relationship: a column typed `answer_status` is the ordinary
three-answer question, `answer_status_or_undecided` is the same list plus the
fourth word, and the `CREATE DOMAIN` line says which is the subset.

**Measured before the shape was adopted**
(`.scratch/ship-archive/rfc-209/
measurements.md`), because a derived domain was
a shape nobody here had run:

- `ALTER COLUMN ... TYPE` onto the nested domain works, and
  `information_schema.columns` then reports `domain_name = answer_status` — the
  column advertises the narrow spelling, not the base.
- The diagnostics stay actionable. `undecided` on a money column is refused with
  `violates check constraint "answer_status_excludes_undecided"`; a word outside
  the vocabulary entirely with `"answer_status_or_undecided_check"`. Both name
  the column's own domain, so neither is worse than the per-table CHECK it
  replaces.
- Retyping rewrites the table — filenode `163072964` -> `163072973` on a
  20,000-row probe — exactly as retyping to a flat domain over TEXT does. There
  is nothing extra to pay for the derivation.

**The pairing rule becomes one function, declared over the BASE type:**

```sql
CREATE FUNCTION value_iff_answered(status TEXT, has_value BOOLEAN)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS
$$ SELECT has_value = (status = 'answered') $$;
```

`TEXT`, not `answer_status`: a domain-typed parameter is re-validated on call,
so a function typed on the narrow domain could not serve the wide one — passing
`undecided` fails with "value for domain answer_status violates check
constraint". Over the base type it serves both (measured), and the vocabulary is
still enforced by each column's own domain. Four copies of an expression become
four call sites of one rule.

**Eight columns retype, not four.** `money_profiles` holds four status columns
and `money_profiles_versions` holds the same four. The history table takes the
domain too, or the vocabulary is still written twice.

**The renderer loses the money from its signature.**
`CoachingService.renderMoneyField(status, value, column, profile)` needed the
profile only to name the row in a `CorruptPersistedValueException`. The three
arms and their exact words move to

```kotlin
fun renderDeclinableAnswer(status: AnswerStatus, value: String?, location: String): String
```

in `service/.../coaching/DeclinableAnswer.kt`. `renderMoneyField` stays as a
four-line wrapper that supplies the one money-specific thing — the cell name,
`"money_profiles.$column (row ${profile.id.value})"` — so the three call sites
and their output are untouched. `test/06`'s block calls the shared renderer
instead of writing a fourth ladder.

**Where the values live in a sibling table, the status is not stored (D24).** A
list-valued answer is ANSWERED exactly when a live row exists, so a stored
`answered` beside zero rows is a contradiction no CHECK can catch — the two
facts are in different tables. CLAUDE.md's own schema convention already settles
it: a derived figure is computed at read time and never stored. The rule is a
second constructor on the type that owns this vocabulary:

```kotlin
// AnswerStatus.Companion
fun of(declinedAt: Instant?, hasValue: Boolean): AnswerStatus
```

A refusal wins over rows, because a family that answered and then asked us to
forget it has declined. It lands here, with its tests and with no caller,
because it is the half of the component `test/06` consumes — and it exists
BEFORE that slice precisely so that slice cannot invent a second version of it
under time pressure.

**`intended_subject_status` retypes onto the wide spelling in this run.** RFC
205 landed that table with its own four-value CHECK, deliberately, as a single
NAMED constraint, because the domain did not exist yet; its own text names this
RFC as the handover. The retype is one `DROP CONSTRAINT` and one
`ALTER COLUMN ... TYPE` on the table and its history, with no data change. It
was to be applied **only if that table was in the base at this run's final
rebase** — RFC 205 was still implementing, and a migration that names a table
which has not landed breaks the corpus for everyone. **It was not there**
(checked at the final rebase, `main@25d8f78f`), so the retype is a follow-on,
written out in the migration's own closing comment: two `ALTER`s, no data
change. Until it runs, `answer_status_or_undecided` has no column of its own —
it is the base the money spelling is derived FROM, which is the whole reason the
three shared words are written once.

### 2. The partial date: a real `DATE` at the period start, plus a precision

Today `students` holds `expected_high_school_graduation_{year,month,day}` as
three SMALLINTs with three CHECKs, assembled by hand in six lines of
`StudentsDao`. `CdsMonthDay` is the cousin with no year. `test/06` was about to
write a third copy.

```sql
CREATE DOMAIN date_precision AS TEXT
    CONSTRAINT date_precision_check
    CHECK (VALUE IN ('year', 'month', 'day'));

CREATE FUNCTION is_period_start(d DATE, p date_precision)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE p
            WHEN 'year'  THEN d = make_date(EXTRACT(YEAR FROM d)::int, 1, 1)
            WHEN 'month' THEN EXTRACT(DAY FROM d) = 1
            ELSE TRUE
          END $$;
```

A table then holds two columns and one CHECK call, and the date is a value the
database can USE: `<= CURRENT_DATE`, `ORDER BY`, a plain B-tree index, and a
range join against `admission_test_administrations.test_date` are each one
predicate with no reassembly. `test/06` needs three of those four and `test/07`
needs the fourth.

**Rejected, and why.**

- _Three SMALLINTs plus one shared validity function._ Cheapest to migrate (drop
  three CHECKs, add one; no data moves; no test touched). It leaves a date
  nothing can order or compare, so every later slice keeps reassembling it with
  `make_date`/`COALESCE`, and it is a second migration of `students` later.
- _A Postgres composite type, or a domain over one._ The DAOs here are
  hand-written JDBC. A composite column comes back as a `PGobject` holding the
  literal `(2026,3,)`, so every read gains a parser for a Postgres wire syntax —
  a hand-rolled format introduced to remove a hand-rolled format. Sub-field
  access needs `(taken_on).year` and expression indexes, and a NULL inside a
  composite is a second kind of absence beside a NULL composite.

**The binding Kotlin constraint (D23), in Ian's words:** _"we must use a custom
type in kotlin, never a data clump of the data and the precision."_ So the
`(date, precision)` pair **never escapes the data-access layer**. The codec is
one object beside `PartialDate`, so the next table's DAO reuses it rather than
re-deriving it:

```kotlin
enum class DatePrecision(val value: String) { YEAR("year"), MONTH("month"), DAY("day") }

object PartialDateColumns {
  fun periodStart(date: PartialDate): LocalDate
  fun precision(date: PartialDate): DatePrecision
  fun decode(periodStart: LocalDate, precision: String): ValidationResult<PartialDate>
}
```

`decode` is where this shape's one danger is disarmed. A stored `2026-01-01`
LOOKS precise, and RFC 186's whole lesson is that the coach must never state a
month we did not record. `decode` returns `YearOnly(2026)` for
`(2026-01-01, 'year')`, and returns `Invalid` for `(2026-03-05, 'year')` — a
date that is not the start of its own period is row corruption, surfaced as
`CorruptPersistedValueException` exactly as a `month=null, day=15` row was
before. **It does not silently shorten** (approved by Ian, 2026-09-14): a June
somebody really recorded would otherwise disappear with nothing to say so.
Nothing above the DAO can see a `LocalDate`, a precision, or a column name; the
only thing above storage is a `YearOnly` with no month to leak.

### 3. `students` migrates in this slice, not later

Shipping a component and leaving the duplicate in place is precisely what Ian
objected to. The migration is bigger than "add two columns, drop three", and
three traps are why.

1. **`students_versions` carries the same three columns**, and
   `StudentsDao.mapStudent` maps rows from BOTH tables (`SELECT *`), so the two
   cannot migrate separately.
2. **`log_student_version()` names the columns in a plpgsql body, which Postgres
   does not dependency-track** — the trap `0094:66-68` recorded and brief 0010's
   DISCOVER preamble repeats. It is restated with `CREATE OR REPLACE` in the
   same migration, or the next write dies with `42703`.
3. **A plain backfill `UPDATE students` fires the triggers**: it would bump
   every student's `version` and write a history row recording a change the
   family never made. The backfill runs between `DISABLE TRIGGER USER` and
   `ENABLE TRIGGER USER`.

Above the DAO there is nothing to change. Measured:
`grep -rn 'expected_high_school_graduation' --include=*.kt` was 43 hits, of
which the only main-source ones were the five inside `StudentsDao`. Every other
layer — `StudentService`, `StudentsResource`, `StudentRoutes`,
`SynthesisService`, `StudentGraduation`, `CoachingService` — already holds a
`PartialDate`.

The bill is in the test corpus: **33 `INSERT INTO students` statements and 2
`UPDATE students` statements across 33 files** (the spec's "~37 across ~33" is
close; the measured number is 35 in 33), plus **one shell harness**,
`bin/db-convos-tests`, which builds a student in raw SQL and which no Kotlin
sweep would have found. They move onto one fixture:

```kotlin
// db/src/testFixtures/kotlin/ed/unicoach/db/dao/StudentRowFixture.kt
object StudentRowFixture {
  fun insert(connection: Connection, studentId: UUID, userId: UUID,
             graduation: PartialDate = DEFAULT_GRADUATION)
  fun updateGraduation(connection: Connection, studentId: UUID, graduation: PartialDate)
}
```

`:db` already publishes test fixtures and `:service` already consumes them, so
this adds no module coupling. After this slice a test says what date it wants,
in the type the product speaks, and the next change of storage costs one file.

### 4. The guard lives outside the files this slice rewrites

Brief 0010's standing rule: a guard inside the artifact under rewrite is not a
guard, because rewriting that file IS the task. Three claims, all asserted from
NEW files.

- **Schema half.** `information_schema.columns` for `students` and
  `students_versions` names both new columns and **none** of the three old ones.
  Both tables are read before anything is asserted, and both are held to a
  column this RFC does not touch, so a typo in a table name cannot pass by
  reading an empty set.
- **Source half.** A sweep of every module's `src/main/kotlin`, with the module
  list PARSED from `settings.gradle.kts` (the `PublisherMoneyColumnsAreGoneTest`
  precedent) so a module added later joins automatically. Two assertions, not
  one, because they are two different claims: the two **column names** may
  appear only in `StudentsDao.kt`, and the **domain's name** only in that file
  plus the two codec files, each listed with why. The sweep asserts it read a
  plausible number of modules and files first, so a wrong root fails rather than
  passing vacuously, and it asserts that the permitted file still names the
  columns, so a stale permit is caught too.
- **The precision claim itself.** A year-precision row decodes to a year with no
  month and no day and an ISO form of `"2026"`; a row whose date disagrees with
  its precision is refused. Asserted both as a unit (`PartialDateColumnsTest`)
  and through the database, by dropping the CHECK inside a transaction and
  reading the row back through the DAO (`StudentsDaoTest`).

And the behaviour pin, also a new file: the graduation sentences RFC 186 landed
are asserted **byte for byte** through `composeSystem` — at all three
precisions, and for students deliberately unlike one another (a junior, a
senior, and one whose graduation is already past, per brief 0010 D22). If one of
those strings moves, this slice did something it promised not to do.

### 5. What this slice does not touch

No coach prompt copy, so `bin/prompt-seed` writes nothing and the
`systemPromptVersion` pin does not move. No new date is rendered for a human, so
brief 0010 D21 ("one date seam, US format", `test/04.5`) gains no new pattern to
consolidate: the only human-facing date strings in this run's diff are the three
RFC 186 graduation sentences it pins unchanged. The ISO wire form is
`PartialDate.toIso()`, unchanged. No money copy, no tool, no door.

## Files Modified

**Added**

- `db/schema/create-answer-status-domains.sql` — the two derived domains, the
  `value_iff_answered` function, the eight-column retype of `money_profiles` /
  `money_profiles_versions`, and (conditionally, see §1) the
  `intended_subject_status` retype. RFC 180 slug, no number; the
  `db/schema/ORDER` line is placed by `ship-order` at land.
- `db/schema/create-partial-date-storage.sql` — the `date_precision` domain, the
  `is_period_start` function, and the `students` / `students_versions` migration
  with its disabled-trigger backfill and trigger-function restatement.
- `db/src/main/kotlin/ed/unicoach/db/models/DatePrecision.kt`,
  `.../models/PartialDateColumns.kt`.
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/StudentRowFixture.kt`.
- `db/src/test/kotlin/ed/unicoach/db/PartialDateStorageHasOneReaderTest.kt`.
- `db/src/test/kotlin/ed/unicoach/db/models/PartialDateColumnsTest.kt`,
  `.../models/AnswerStatusOfTest.kt`.
- `service/src/main/kotlin/ed/unicoach/coaching/DeclinableAnswer.kt`.
- `service/src/test/kotlin/ed/unicoach/coaching/DeclinableAnswerTest.kt`,
  `.../coaching/GraduationSentenceIsUnchangedTest.kt`.

**Changed**

- `db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt` — `mapGraduationDate`
  and `gradDateColumns` read and write the two columns through
  `PartialDateColumns`; the `mapCreateUpdateError` KDoc names the new
  constraint.
- `db/src/main/kotlin/ed/unicoach/db/models/AnswerStatus.kt` —
  `of(declinedAt, hasValue)`, and a KDoc naming the domains as the schema's one
  home for the vocabulary.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` —
  `renderMoneyField` becomes a wrapper over `renderDeclinableAnswer`.
- `bin/db-convos-tests` — its raw student insert.
- 33 test files — the raw `INSERT INTO students` / `UPDATE students` statements
  move onto `StudentRowFixture`.
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentsDaoTest.kt` — the two tests
  that assert the OLD storage shape are rewritten against the new constraints.
  These are assertions about storage, which is what this slice changes by
  design; **no assertion about behaviour above the DAO moves**, and one that had
  to would mean this slice had changed behaviour and the run should stop.
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingTestDb.kt` — its student
  seeder takes a `PartialDate`.
- `db/schema/ORDER` — two lines, placed by `ship-order` at land.

**Not touched:** `.claude/**`, `product/**`, `prompts/coach-system-prompt.md`,
`service/src/main/resources/service.conf`, `CdsMonthDay` and every CDS deadline
reader, `admission_test_administrations`, the money copy, iOS, `admin-web`,
`public-web`.

## Implementation Plan

1. `create-partial-date-storage.sql`: domain, function, both tables, backfill
   inside `DISABLE TRIGGER USER` / `ENABLE TRIGGER USER`, `SET NOT NULL`, the
   period-start CHECK, drop the three old columns on both tables,
   `CREATE OR REPLACE FUNCTION log_student_version()`. `bin/db-reset` to prove
   it applies.
2. `DatePrecision`, `PartialDateColumns` and its unit test; `StudentsDao`.
3. `StudentRowFixture`; move the 35 raw statements across 33 files and the one
   shell harness onto it. The batch is written to the run scratch BEFORE the
   edits begin, so a stalled pass leaves a reconcilable list.
4. The guard file and the byte-identical pin.
5. `create-answer-status-domains.sql`, after probing the derived-domain shape
   (retype, diagnostics, rewrite). `bin/db-reset` again.
6. `AnswerStatus.of`, `DeclinableAnswer.kt`, the `CoachingService` wrapper,
   their tests.
7. `nix develop -c bin/format -c`, tiered review, then `nix develop -c bin/test`
   (no module, no `-f`), with database contention checked before and after.
8. At the final rebase: check whether `intended_subject_status` is in the base.
   Retype it if so; record it as the follow-on if not.

## Tests

**New**

- `PartialDateColumnsTest` — round-trips all three precisions; refuses a date
  that is not its own period start; refuses an unknown precision; and asserts a
  year-only date has no month and no day to leak.
- `AnswerStatusOfTest` — the three arms of `of(declinedAt, hasValue)`, including
  the case that makes it worth having: a refusal beside a live row is
  `DECLINED`, not `ANSWERED`.
- `DeclinableAnswerTest` — the three strings literally; a declined or unanswered
  field never renders a stale value; the `answered`-with-null corruption path
  carries the caller's cell name as data and in the message.
- `PartialDateStorageHasOneReaderTest` — the schema half, the two source-sweep
  halves, and the precision claim (§4).
- `GraduationSentenceIsUnchangedTest` — the RFC 186 sentences, byte for byte, at
  three precisions and across four differently-shaped students.

**Changed**

- `StudentsDaoTest` — two storage-shape tests rewritten; every other test in the
  file asserts through `PartialDate` and is untouched.
- 33 test files move their raw student write onto `StudentRowFixture`; no
  assertion in any of them changes.

**Unchanged and load-bearing:** `CoachingServiceTest`'s date-block tests,
`HighSchoolYearTest`, `StudentServiceTest`, `StudentRoutingTest`,
`StudentsResourceTest`, `MoneyProfilesDaoTest`, `MoneyProfileChatToolTest` — all
speak `PartialDate` and `AnswerStatus`, and all pass with no edit beyond the
fixture swap.

## Rollback

Two migrations, both forward-only in the house style. There is no behaviour to
roll back: no prompt version is cut, no tool changes, no copy moves. A revert of
the code commit restores the old DAO but not the dropped columns, so a true
rollback is a new migration that re-adds the three columns and backfills them
from the pair — mechanical, and the same `PartialDateColumns` codec in reverse.
The answer-status half rolls back by retyping the eight columns to `TEXT` and
re-adding the four CHECKs; the stored values never change, in either direction.
