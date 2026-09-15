# RFC 215 — `fafsa_filing_deadlines`: one store for every FAFSA date

Slice `fafsa/01/the-deadline-table` (brief 0012,
`product/0012-fafsa-support/spec.md`).

## Why

Brief 0012 D4, amended by Ian: _"we don't make ppl go read dry federal and state
documents; Unicoach consolidates and shares data. In fact, the public html page
should show a table of the dates for each state and region."_ That decision
needs a store, because the public page (`fafsa/04`) and the coach (`fafsa/05`)
must read the SAME dates. Two copies of a date eventually disagree, and a family
sees only one of them.

This slice builds the store and nothing else: the table, its two enums, the row
model, the DAO and the filing-year clock. **No rows** — they are `fafsa/02`.
**No door** — the door is `fafsa/04`, and this slice's acceptance criteria say
so explicitly. Nothing a family sees changes.

## Detailed Design

### The table

`db/schema/create-fafsa-filing-deadlines.sql`, one line appended to
`db/schema/ORDER` by `ship-order` at land time (RFC 180: no migration number).

One row per `(award_year, state, program, event)`. The DDL is reproduced in full
in the approval gate for this run (brief 0001 D10) and lives in the file above;
the load-bearing decisions are:

- **Federal rows live in the same table with `state IS NULL`.** A `scope` column
  would be a stored derivation of `state IS NULL` (CLAUDE.md). The natural key
  therefore needs `NULLS NOT DISTINCT`, the shape `cohort_money_stats` and
  `cohort_population_counts` already use for an axis whose NULL means something.
- **Three kinds, with a value-iff-kind CHECK.** `date` carries a day; `wording`
  carries the publisher's RULE and never a day somebody guessed; `none` is a
  VERIFIED absence with a citation. A missing row is the unknown (D9), and the
  page turns that into our own sentence.
- **`deadline_as_published` is NOT NULL on every row**, beside the optional
  `deadline_date`. The name is the landed one for this idea —
  `closingAsPublished` beside `closingResolved` in
  `AdmissionTestTimelineChatTool`, emitted as `closing_as_published`. A `DATE`
  cannot hold "11:59 p.m. Pacific", and a reader that saw only our parse could
  attribute our reading to the publisher — the `closingAsPublished` /
  `closingResolved` argument, one table along.
- **Four events, including `corrections_deadline`** (Ian, at the approval gate):
  the last day to fix an error on a form already filed — 11 September 2027 for
  the current cycle per the Federal Register, which `studentaid.gov` prints as
  12 September. That one-day disagreement is STORED with its source, one row per
  publisher, not resolved by us. The corpus in
  `discover/state-deadlines-merged.csv` holds no federal rows at all, so every
  federal row including this one is typed in `fafsa/02`.
- **`event` stays a per-table CHECK, with a stated promotion trigger.** RFC 213
  made `link_status` a domain when a SECOND table asked the question. One column
  asks this one; the column comment records that the day a second table needs
  the same vocabulary, this CHECK becomes `CREATE DOMAIN aid_deadline_event` and
  both columns take it — never a second copy of the list.
- **The citation is two foreign keys into `external_documents`** (RFC 213, brief
  0012 D16): the agency's page (NOT NULL) and the archived address the row was
  read through (NULL), with the CHECK that an archive is not its own source.
  This is `adopt-external-documents-for-state-programmes.sql`'s shape, copied.
  This table ships no `source_url` of its own.
- **`publisher` stays a column of this row.** `external_documents` deliberately
  carries no publisher, because "the FACT already knows its publisher" (RFC
  213); the public table renders this string as its link text, and without it
  every row would cite a bare url.
- **A cycle-containment CHECK on `deadline_date`, and a matching one on
  `checked_on`.** A 2026-27 date typed under a 2027-28 key is the one mistake
  that makes this table worse than no table. The window is
  `[award_year - 1 July 1, award_year + 1 Dec 31]`: the lower bound admits the
  October opening of the cycle and refuses last cycle's 2 March, which is the
  typo itself. `checked_on` takes the same lower bound, because an agency
  publishes a cycle's deadline in the months before it opens and not eighteen
  months before. Both are deliberately COARSE and cannot be tight: the 2026-27
  corrections deadline (11 September 2027) falls eleven months after the 2027-28
  form opened, so no pair of dates separates two cycles and a stricter window
  would refuse true rows. These are the only constraints here with no direct
  precedent in the tree; `make_date` is `IMMUTABLE`, so both are checkable in
  SQL and not only in the generator.
- **No foreign key to `us_states`**, for the reason
  `create-admission-test-state-programmes.sql` already argues at its own `state`
  column: `us_states` is filled by the ingest, not by a migration, so a seed
  migration referencing it cannot apply to a freshly migrated database. Brief
  0010's `test/05.3/own-our-codebooks` adds the key to both tables together.
  Until then membership is `UsStateCodes.parse`'s job, in the generator and the
  seed test.

### The Kotlin side

`db/src/main/kotlin/ed/unicoach/db/models/`, the `AidForm` /
`AdmissionTestStateProgramme` shapes:

- `AidDeadlineEvent` — `FORM_OPENS`, `PRIORITY_DEADLINE`, `FINAL_DEADLINE`,
  `CORRECTIONS_DEADLINE`, with `value` and a `fromValue` companion. Its KDoc
  says why publishers' words are NOT in the enum: fifty agencies name the same
  event fifty ways, and a caller branching on the code to fix its own wording
  should have read `deadlineAsPublished`.
- `AidDeadlineKind` — `DATE`, `WORDING`, `NONE`, KDoc carrying D4's reason.
- `PublishedDeadline` — a sealed interface (`OnDate`, `InWords`,
  `NonePublished`), each carrying `deadlineAsPublished`, so a kind without its
  date does not typecheck. The `ScoreRelease` / `SchoolDayTestChoice` precedent.
- `DeadlineJurisdiction` — `Federal` / `State(uspsCode)` (one name for the
  value, not a `code` parameter forwarding to it), so "federal" is not a null
  anybody has to remember.
- `FafsaFilingDeadline` / `NewFafsaFilingDeadline` / `FafsaFilingDeadlineId`,
  the row model, carrying `sourceUrl` and `archiveUrl` read through the joins
  exactly as `AdmissionTestStateProgramme` does.
- `FafsaFilingCycle` — the clock. `currentFilingYear(clock)`, and a
  `currentFilingYear(today: LocalDate)` overload it delegates to, so the
  boundary months can be probed without building a fixed `Clock`; the first
  month of the window is the named `FILING_OPENS_IN`, stated once so the rule
  and its tests cannot drift. It returns the award year whose FAFSA families are
  filling in NOW: October or later means next year's cycle, otherwise this one.
  It is NOT `FederalAidPolicyService.currentAwardYear()`, whose July 1 rule
  answers "which Pell table is in force" and would serve last cycle's deadlines
  for nine months of every year. It lives in `:db` and not `:service` because
  the seed guard that enforces D11 is a `:db` test and cannot see `:service`;
  `HandTypedReferenceData` is the precedent for a rule-home in that package.

### The DAO

`FafsaFilingDeadlineDao`, modelled on `AdmissionTestStateProgrammeDao`: a
stateless `object`, one `SqlSession` per call, the transaction boundary owned by
the caller.

- `listByAwardYear(session, awardYear)` — every row of one cycle, ordered
  `state NULLS FIRST, program, event`, so the federal rows lead. INNER join to
  `external_documents` for the source, LEFT join for the archive.
- `upsert(session, row)` — on the natural key, resolving both urls through
  `ExternalDocumentsDao.idOf` (never its upsert: this path knows the urls and
  knows nothing about whether they resolve, so it must not overwrite a swept
  status with "we never asked", RFC 182). For fixtures and for the fafsa/02
  generator's own test, not for any request path.

Expiry is NOT decided here. `fafsa/03`'s `FafsaDeadlineService` owns the
three-arm refusal; this layer returns rows and their dates.

## Files Modified

| File                                                                                 | Change                                                                            |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------- |
| `db/schema/create-fafsa-filing-deadlines.sql`                                        | NEW — the table, its constraints, the natural-key index, the `updated_at` trigger |
| `db/schema/ORDER`                                                                    | one appended line (written by `ship-order` at land)                               |
| `db/src/main/kotlin/ed/unicoach/db/models/AidDeadlineEvent.kt`                       | NEW enum                                                                          |
| `db/src/main/kotlin/ed/unicoach/db/models/AidDeadlineKind.kt`                        | NEW enum                                                                          |
| `db/src/main/kotlin/ed/unicoach/db/models/FafsaFilingDeadline.kt`                    | NEW — row model, id, `PublishedDeadline`, `DeadlineJurisdiction`, `New…`          |
| `db/src/main/kotlin/ed/unicoach/db/models/FafsaFilingCycle.kt`                       | NEW — `currentFilingYear`                                                         |
| `db/src/main/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineDao.kt`                    | NEW — `listByAwardYear`, `upsert`                                                 |
| `db/src/test/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineDaoTest.kt`                | NEW                                                                               |
| `db/src/test/kotlin/ed/unicoach/db/FafsaFilingDeadlineSchemaTest.kt`                 | NEW — the constraint tests                                                        |
| `db/src/test/kotlin/ed/unicoach/db/models/FafsaFilingCycleTest.kt`                   | NEW — the boundary months                                                         |
| `db/src/main/kotlin/ed/unicoach/db/dao/ExternalDocumentsDao.kt`                      | `citationIds` — the shared (source, archive) resolution                           |
| `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeDao.kt`            | calls `citationIds` instead of its own copy of it                                 |
| `service/src/test/kotlin/ed/unicoach/coaching/aid/FilingCycleVersusAwardYearTest.kt` | NEW — the two clocks asked together                                               |

No `:rest-server`, no `public-web`, no prompt change. The `:service` file is a
TEST only, and review is the reason it exists: the contrast between the filing
clock and the July-1 award year was first asserted in `:db` against a hand copy
of the July rule, which keeps passing after the real rule moves. `:service` is
the only module that can see both, so the case that calls both real functions
lives there.

Two changes review added to the plan, both recorded here rather than left as
surprises in the diff:

- **`ExternalDocumentsDao.citationIds`.** The twelve lines that turn a (source
  url, optional archive url) pair into ids — comment included — were identical
  in `AdmissionTestStateProgrammeDao`. This RFC states the promotion rule for a
  vocabulary ("shared the day a SECOND table asks the same question") and review
  pointed out it was not applying that rule to its own code. Both DAOs now call
  one function, and the RFC 182 cite-don't-sweep policy is stated once.
- **`fafsa_cycle_opens_on(academic_year)`.** The cycle's lower bound is asked by
  two CHECKs and was typed twice, so tightening one would not have carried to
  the other. It is one `IMMUTABLE` SQL function now.

One duplication is recorded and deliberately NOT fixed here: the `sourceUrl` /
`archiveUrl` / `checkedOn` / `expiresOn` provenance quartet is now in eight row
models with no shared `Citation` type. That is a repo-wide shape and a repo-wide
change; `fafsa/03` must not become the ninth site with its own hand-rolled
expiry comparison, which is why it is written down.

## Implementation Plan

1. Write the migration; `nix develop -c bin/db-reset` to prove it applies to a
   freshly recreated test database.
2. The two enums, the sealed pair, the row model and the id.
3. `FafsaFilingCycle`, with its test first — the boundary months are the whole
   point.
4. The DAO, following `AdmissionTestStateProgrammeDao` line for line where the
   shapes agree.
5. The schema tests. Every named constraint is asserted BY NAME; the seven
   single-column CHECKs share one table-driven test that reports every mismatch
   in one pass, on the sibling corpus's rule, rather than seven near-identical
   methods.
6. `nix develop -c bin/test`.

## Tests

The acceptance criteria of `fafsa/01`, one test each:

- **The migration applies to a freshly recreated test database** — `bin/test`
  re-inits and migrates before Gradle, so every DB-backed test proves this; the
  DAO test is the explicit one.
- **Value-iff-kind rejects both directions** — a `wording` row carrying a date,
  and a `date` row carrying none.
- **A date outside its own cycle is rejected** — last cycle's 1 March typed
  under this cycle's key.
- **`form_opens` cannot be `none`.**
- **The natural key collides on federal rows** — two rows with `state IS NULL`,
  same year/program/event, upsert to ONE row. This is what `NULLS NOT DISTINCT`
  buys, and without the test a plain unique index would pass every other case.
- **An archive equal to its source is rejected**; **a row with no source
  document cannot be written** (NOT NULL).
- **`FafsaFilingCycle`** — September and October of the same year return
  different cycles, and the rule differs from `currentAwardYear`'s July
  boundary. No test asserts that a second cycle is closed, because two are
  legitimately open.
- **All four event values are writable**, `corrections_deadline` included, and a
  fifth is rejected.
- **The DAO round-trips** a `date`, a `wording` and a `none` row, returns the
  source url through the join, returns the archive url as null where absent, and
  orders federal rows first.

Reported as executed counts from the JUnit XML, never `grep -c "@Test"`.
