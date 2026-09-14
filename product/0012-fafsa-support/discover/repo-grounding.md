# DISCOVER — repo grounding for brief 0012 (FAFSA support), D4 as amended

Read-only worktree `/Users/ian/Work/unicoach-docs-0012-fafsa` (clean `main`, tip
`b6966d4b`), read 2026-09-14/15. Every line number is that tree's, except §7's
brief-0011 lines, which are the ROOT checkout `/Users/ian/Work/unicoach` (0011's
docs are uncommitted there — see §7.1). Nothing was committed and no tracked
file was modified.

**Method.** Four parallel read-only sweeps (public-web wiring + page; DDL +
staleness; the seed generator; coverage + prompt collision), each reading source
directly and quoting it. **No Gradle run, no database, no server** — the
coverage question (§6) was settled statically with `git show` + `csv.DictReader`
over three commits of one seed file, so the one permitted DB attempt was not
spent. All proposed SQL and Kotlin below is **unexecuted** and marked UNVERIFIED
where it is a design claim rather than a reading.

**This report does not repeat `research/repo-feasibility.md`.** It goes under it
and, in three places, corrects it — see "Corrections" at the end.

---

## 1. ONE SOURCE, TWO SURFACES

### 1.1 `public-web` talks to no HTTP. It is in-process over `:service` and `:db`.

`public-web/build.gradle.kts:11-32` states the dependency AND its precedent:

```kotlin
implementation(project(":db"))
implementation(project(":auth"))
// RFC 155 D-E: the Family Cost Report renders the SAME computation the coach
// reads in chat (CollegeCostService), in-process behind the CostReportSource
// port — the admin-web precedent for a web module depending on :service.
implementation(project(":service"))
```

`grep -rln "io.ktor.client" public-web/src/main` returns nothing, and only
`ktor.server.*` artifacts are declared (`:20-23`): **`public-web` cannot call
`rest-server`; the option does not exist without adding a dependency.**

The composition root is `public-web/.../Application.kt:34-58`:

```kotlin
val database = Database(DatabaseConfig.from(config).getOrThrow())
val emailVerifier: EmailVerifier = DbEmailVerifier(database)
val costReportSource: CostReportSource = ServiceCostReportSource(database, CollegeCostService(database))
```

with the KDoc (`:25-32`) saying "both in-process rather than one service hopping
to the other over HTTP". `public-web` therefore **already owns a `Database` at
boot**, for the process lifetime, on the shared `db.conf` block (`:37`). A
FAFSA-deadline read needs **no new dependency, no new config key, no new
connection lifecycle** — one more collaborator constructed at `:50-52` and
threaded through `publicWebModule` (`:75-84`) → `installPublicWebRouting`
(`Routing.kt:90-94`), plus one default in the test helper
`public-web/src/test/.../FakeEmailVerifier.kt:38-45`, which exists precisely so
"adding a parameter to `publicWebModule` is then one edit here rather than
twenty mechanical ones" (`:28-37`).

The `:service` side of the same precedent is explicit —
`service/.../costs/CollegeCostService.kt:1263-1271`:

```kotlin
 * Public because the only caller outside `:service` is `public-web`'s
 * [CostReportSource] adapter, which is an in-process port by D-E rather than
 * an HTTP hop.
fun readInSession(session: SqlSession, studentId: StudentId): CollegeCostProfile
```

### 1.2 The cheapest honest wiring for one shared deadline table

| Layer                             | Module                                                          | Artifact                                                                                                                                                   | Precedent                                                              |
| --------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Table + seed                      | `db/schema/*.sql`, `db/seed/fafsa-deadlines/*.csv`, `bin/<gen>` | `fafsa_filing_deadlines` (§2)                                                                                                                              | `create-admission-test-calendar.sql`, `bin/test-calendar-seed`         |
| DAO                               | `:db`                                                           | `FafsaDeadlinesDao` beside `AdmissionTestCalendarDao.kt`                                                                                                   | `AdmissionTestCalendarDao`, `PolicyParametersDao`                      |
| **Read service — the ONE answer** | `:service`                                                      | `FafsaDeadlineService(database, clock)` returning the sealed `FafsaDeadlines` (§4.5)                                                                       | `TestTimelineService.kt:289-336`, `FederalAidPolicyService.kt:148-184` |
| Coach door                        | `:service` + `rest-server`                                      | a chat tool over that service, registered beside `AdmissionTestTimelineChatTool(TestTimelineService(database))` (`rest-server/.../Application.kt:403-408`) | same line                                                              |
| Page door                         | `public-web`                                                    | same `FafsaDeadlineService(database)` built at `Application.kt:50-52`, passed through the module, `get("/fafsa")` beside `Routing.kt:117-118`              | `Routing.kt:116-118`                                                   |

**"Page and coach cannot disagree" is bought by the read service and a copy
object, not by the port.** Both doors call the same `:service` function and
render the values it returns: the page never re-derives "is this expired", never
re-words "until funds run out", never applies its own region map. That is the
house rule already stated twice in the cost code — `CostReportPage.kt:1454-1458`
("A second list here is how the page and the coach came to narrate the same
filing in two different orders") and `:1118-1126` ("a second rule in a renderer
would be free to disagree with the number the same page prints").

**Is `CostReportSource`/`ServiceCostReportSource` the right precedent? Half.**
Right: a narrow port with a hand-written fake, so page-rendering tests stay
DB-free (`CostReportPageTest.kt:92` installs `FakeCostReportSource`; only
`VerifyEmailRoutingTest.kt:51-69` builds a real `Database`), and a
`Result`-returning read so a DB fault renders as an outcome rather than leaking.
Wrong: its SHAPE. `getByShareToken(rawToken): Result<CostReportOutcome>` plus
`MissReason`'s "four indistinguishable misses" (`CostReportSource.kt:40-79`) is
credential-resolution machinery; a deadline table has no token, no student, no
secret, and no second read to be consistent with
(`ServiceCostReportSource.kt:19-31` says the single session exists because a
share can be revoked mid-read).

So: **one interface in `public-web`, one method, no arguments** —
`interface FafsaDeadlineSource { suspend fun deadlines(): Result<FafsaDeadlines> }`
— with a three-line in-process adapter and a `FakeFafsaDeadlineSource` in
`public-web/src/test`. The domain type is `:service`'s, exactly as
`CostReportOutcome.Found` carries `:service`'s `CollegeCostProfile`
(`CostReportSource.kt:3,52-54`). Skipping the port is defensible but costs the
DB-free render tests, and this page is table rendering with many branches.

**Decide explicitly at spec time:** the FAFSA route must NOT copy
`reportRoute`'s `setLinkHolderPrivacyHeaders()` / `no-store`
(`Routing.kt:190-193`). It is a plain `get("/fafsa")` beside `/terms`, and it
must be INDEXABLE — `siteLayout(...)` with `noindex` left at its default `false`
(`Layout.kt:32-47`). The brief's distribution argument dies if the explainer is
`noindex`.

---

## 2. THE DEADLINE TABLE

### 2.1 The four decisions under the DDL

**(a) `academic_year` domain, start-year convention — not the calendar's `cycle`
label.** `aid_form_requirements` (schema 0087) and
`policy_parameters.award_year` (0077) both key FAFSA-adjacent facts by start
year; `admission_test_administrations` stores a `'2026-27'` TEXT label only
because College Board publishes one. A coach answer that joins deadlines to Pell
figures must not straddle two year spellings. Consequence for the generator: the
whole cycle-format validator is deleted, not adapted (§3).

**(b) Citation per row (`source_name` + `source_url`, the 0077 precedent), NOT
`source_documents` (0087) — and this is decisive, not stylistic.**
`source_documents.college_id` is `NOT NULL` and a state agency belongs to no
college; its `source` is the closed `money_source` publisher domain; and the
tables citing it are rebuilt wholesale by the CDS ingest, while these rows are
seeded by migration. The entity is simply unavailable to this table.

**(c) `us_states` is the right vocabulary and an FK to it is a TRAP.**
`db/schema/0060.create-codebook-reference-tables.sql:92-103` already carries
`usps_code`, `name`, `jurisdiction_kind`, `ipeds_region -> ipeds_regions.slug`,
so **D4's "grouped by region" needs no new vocabulary** — join
`us_states.ipeds_region -> ipeds_regions.name` at read time. But `us_states`
rows are inserted by **no migration**; they arrive from the codebook phase of
`bin/ingest-colleges` (`:24-25`). A seed migration with
`REFERENCES us_states(usps_code)` could not apply on a freshly migrated database
— including the test DB `bin/test` recreates every run, which never ingests.
Membership therefore goes where `UsStateCodes.kt:7-11` says it goes ("MEMBERSHIP
is the boundary, not a two-letter shape ... `^[A-Z]{2}$` admits 676 strings and
59 of them are legal"): `UsStateCodes.parse` in the generator and the seed test,
with the regex as the coarse DB backstop. _(UNVERIFIED by execution; inferred
from the absence of any INSERT plus the ingest's own docs.)_

**(d) Three kinds, not two.** D4 names date and wording; a page that prints
every state needs a third, `none`. `aid_form_requirements` can let silence mean
"not listed" (`0087:281-286`) because the reader asked about one school. Here
the page asserts completeness, so "this state publishes no deadline" and "nobody
checked" must not render as the same empty cell — a verified absence is a row
with a citation.

Two further shape calls: **federal rows live in the same table with
`state IS NULL`** (a `scope` column would be a stored derivation, which
CLAUDE.md forbids; Kotlin models it as a sealed `DeadlineJurisdiction`), which
forces `NULLS NOT DISTINCT` on the natural key — precedented twice, at
`0083.create-canonical-money-tables.sql:286-291` and `0085:165-171`, both as a
`CREATE UNIQUE INDEX`. And **`published_text` is NOT NULL on every row** beside
an optional `deadline_date`, the `ResolvedDeadline` precedent
(`TestTimelineService.kt:83-86`: `closingAsPublished` beside `closingResolved`,
"so the coach can[not] attribute our arithmetic to the school") — it is also the
only place a time zone can live, which a `DATE` cannot hold.

### 2.2 THE DDL — copy-pasteable, for Ian's /ship gate (brief 0001 D10)

File: `db/schema/create-fafsa-filing-deadlines.sql`, with its line appended to
`db/schema/ORDER`.

```sql
-- fafsa_filing_deadlines -- the FAFSA dates a family actually has to hit
-- (brief 0012 D4, slice fafsa/NN).
--
-- One row per (award year, jurisdiction, program, event): when the form opens,
-- and the deadlines the federal government and each state publish for the aid
-- that FAFSA unlocks. This is the table the public page's state-by-state table
-- renders AND the table the coach speaks from -- one store, because a page and
-- a coach that hold separate copies of a date will eventually disagree about
-- one, and the family only sees one of them (D4).
--
-- HAND-MAINTAINED REFERENCE DATA (brief 0010 D19, the RFC 197 precedent).
-- There is no machine-readable feed for state FAFSA deadlines: each state
-- agency publishes HTML. So every row is typed by a human from the agency's own
-- page, committed as db/seed/fafsa-deadlines/deadlines.csv so that a refresh is
-- reviewable in a diff, and GENERATED into a per-cycle seed migration from that
-- CSV. Never hand-edit a generated seed; edit the CSV and re-run the generator.
-- A corrected row lands as a NEW generated seed upserting on the natural key,
-- never as an edit to a landed one.
--
-- THE AWARD YEAR IS THE ROW'S IDENTITY, NOT ITS DECORATION. State deadlines
-- RECUR ANNUALLY, so last cycle's row is not merely old -- it is a different
-- date that LOOKS like this cycle's. A passed deadline inside the current
-- cycle is still a fact worth showing ("Texas closed on 15 January"); a
-- 2026-27 row shown in the 2027-28 cycle is a lie a family can act on. Every
-- read filters on award_year first and expiry second, and the seed guard fails
-- the build when the committed file has no rows for the cycle now being filed.
--
-- EVERY ROW EXPIRES (D7). Past expires_on the coach and the page REFUSE to name
-- a date and point at studentaid.gov and the state agency, exactly as
-- TestTimelineService refuses; and a test fails when a committed row of the
-- current cycle is within StateFafsaDeadline.DEADLINE_REFRESH_LEAD of its
-- expires_on. That constant is the one home for the number; this comment names
-- it rather than repeating it.
--
-- Reference-table shape (0060/0077/RFC 197 precedent): unversioned, no soft
-- delete, no OCC, created_at/updated_at with the plain
-- update_colleges_timestamp() trigger.
--
-- `award_year` is the academic_year DOMAIN (0087 D14): the FIRST calendar year,
-- so 2027 IS the 2027-28 FAFSA. The "2027-28" label is rendered at read time by
-- AcademicYear and never stored -- unlike admission_test_administrations.cycle,
-- which stores a label because College Board labels its calendar with one.
--
-- NO FigureStatus AND NO ASSURANCE TIER, for the reason RFC 197 gives: that
-- vocabulary belongs to the canonical money tables, whose rows record a
-- publisher's suppression and imputation flags (RFC 179). A deadline is not a
-- money figure -- it is published, in which case it is here with the page it was
-- read from, or it is not, in which case the row says so in the publisher's own
-- words.
--
-- NO FOREIGN KEY TO us_states, DELIBERATELY. us_states is filled by the
-- codebook phase of bin/ingest-colleges (RFC 147), not by any migration, so a
-- freshly migrated database -- the test database that bin/test recreates on
-- every run included -- has none of its rows, and a seed migration referencing
-- it could not apply. Membership in the 59 published USPS codes is enforced
-- where UsStateCodes.kt says it belongs: by the generator over the authored
-- CSV and by the seed test. The regex below is the coarser DB-level backstop
-- that KDoc already asks for. The region grouping the public page needs is
-- still us_states.ipeds_region -> ipeds_regions.name, joined at READ time in a
-- database that has been ingested.

CREATE TABLE fafsa_filing_deadlines (
    id             UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    award_year     academic_year NOT NULL,

    -- The jurisdiction. NULL means FEDERAL: the federal open date and the
    -- federal deadline belong in this table because the page and the coach read
    -- the two together, and a `scope` column beside this one would be a stored
    -- derivation of `state IS NULL` (CLAUDE.md: derived figures are computed at
    -- read time, never stored). Kotlin models the pair as one sealed value, so
    -- "federal" is not a null anybody has to remember.
    state          TEXT          NULL,

    -- The program as ITS OWN publisher names it -- 'Cal Grant', 'TEXAS Grant',
    -- 'Federal student aid'. A source-defined name, stored raw (CLAUDE.md), so
    -- it is neither a slug nor an enum. It is in the natural key because a
    -- state with three grant programs has three deadlines, and collapsing them
    -- to one would pick a winner no source picked.
    program        TEXT          NOT NULL,

    -- OUR OWN enumeration, so TEXT + CHECK IN (...) with exactly one Kotlin
    -- enum class AidDeadlineEvent(val value: String) carrying a fromValue
    -- companion (CLAUDE.md, the IncomeBand/AidForm precedent).
    event          TEXT          NOT NULL,

    -- OUR OWN enumeration too, and the reason this table exists in this shape.
    -- 'date': the publisher names a day, and it is in deadline_date.
    -- 'wording': the publisher names a RULE and not a day -- "as soon as
    --            possible after the form opens", "until funds are exhausted".
    --            It is carried as those words and is NEVER flattened into a
    --            fake date; a made-up 1 March under a "check with your state"
    --            rule is precisely the invented fact this table exists to
    --            prevent (D4).
    -- 'none':   we READ the state's page on checked_on and it publishes no
    --           deadline for this program at all. A verified absence, with a
    --           citation -- not our silence. It is a row and not a missing row
    --           because the public page prints every state, so an empty cell
    --           would be unreadable: "this state has none" and "nobody checked"
    --           must not look the same to a family.
    deadline_kind  TEXT          NOT NULL,

    -- The day, when there is one. NULL for every other kind; see the
    -- value-iff-kind CHECK below.
    deadline_date  DATE          NULL,

    -- What the page says, VERBATIM, on every row whatever its kind: "March 2,
    -- 2027, 11:59 p.m. Pacific", "as soon as possible after October 1",
    -- "no state deadline is published". Both halves ride for the reason
    -- ResolvedDeadline carries closingAsPublished beside closingResolved: a
    -- reader that saw only our parsed date could attribute our reading to the
    -- publisher, and a DATE column cannot hold the hour or the time zone the
    -- state actually enforces.
    published_text TEXT          NOT NULL,

    -- The citation, per row (the 0077 precedent, not the 0087 source_documents
    -- entity): source_name is the SPOKEN name of the page -- "California
    -- Student Aid Commission, Cal Grant deadlines" -- and source_url is that
    -- page. source_documents is not available to this table: its college_id is
    -- NOT NULL and a state agency belongs to no college, its `source` is the
    -- closed money_source publisher domain, and the tables that cite it are
    -- rebuilt wholesale by the CDS ingest while these rows are seeded by
    -- migration.
    source_name    TEXT          NOT NULL,
    source_url     TEXT          NOT NULL,

    -- The day a human read source_url, and the day this row stops being
    -- believed.
    checked_on     DATE          NOT NULL,
    expires_on     DATE          NOT NULL,

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- The coarse DB-level backstop UsStateCodes.kt asks for. ^[A-Z]{2}$ admits
    -- 676 strings and 59 of them are legal, so MEMBERSHIP is checked by the
    -- generator and the seed test with UsStateCodes.parse; this stops the
    -- lower-case and three-letter cases at the write boundary.
    CONSTRAINT fafsa_filing_deadlines_state_format_check
        CHECK (state IS NULL OR state ~ '^[A-Z]{2}$'),

    CONSTRAINT fafsa_filing_deadlines_event_check
        CHECK (event IN ('form_opens', 'priority_deadline', 'final_deadline')),

    CONSTRAINT fafsa_filing_deadlines_kind_check
        CHECK (deadline_kind IN ('date', 'wording', 'none')),

    -- Value-iff-kind (the 0087 value-iff-status shape): a date exists exactly
    -- when the kind bears one. This is what stops a 'wording' row quietly
    -- growing a date somebody guessed, and stops a 'date' row with nothing in
    -- it reaching the page as a blank cell.
    CONSTRAINT fafsa_filing_deadlines_date_iff_kind_check
        CHECK ((deadline_date IS NOT NULL) = (deadline_kind = 'date')),

    -- A form that opens does open. 'none' is a statement about a deadline a
    -- publisher does not set; it is not a thing to say about an opening date,
    -- and a row that said it would render as "the FAFSA does not open".
    CONSTRAINT fafsa_filing_deadlines_opening_is_dated_check
        CHECK (event <> 'form_opens' OR deadline_kind <> 'none'),

    -- Every row's date belongs to its OWN cycle. The 2027-28 FAFSA opened in
    -- 2026 and closes 30 June 2028, so a date outside [award_year - 2 July 1,
    -- award_year + 1 Dec 31] is last cycle's date typed under this cycle's key
    -- -- the one mistake that makes this table worse than no table, because a
    -- family acts on it. make_date is IMMUTABLE, so the bound is checkable
    -- here and not only in the generator.
    CONSTRAINT fafsa_filing_deadlines_date_in_cycle_check
        CHECK (deadline_date IS NULL
               OR deadline_date BETWEEN make_date(award_year::INT - 2, 7, 1)
                                    AND make_date(award_year::INT + 1, 12, 31)),

    -- NOT NULL is not enough: an empty string is a value-shaped absence, and a
    -- citation rendered from one is a link to nowhere (0087's words, 0077's
    -- CHECKs).
    CONSTRAINT fafsa_filing_deadlines_program_nonempty_check
        CHECK (program <> ''),
    CONSTRAINT fafsa_filing_deadlines_published_text_nonempty_check
        CHECK (published_text <> ''),
    CONSTRAINT fafsa_filing_deadlines_source_name_nonempty_check
        CHECK (source_name <> ''),
    CONSTRAINT fafsa_filing_deadlines_source_url_check
        CHECK (source_url ~ '^https://'),

    CONSTRAINT fafsa_filing_deadlines_expiry_after_checked_check
        CHECK (expires_on > checked_on),

    -- A row cannot have been read from a page before the page could have said
    -- it: the cycle's own window again, one bound only, because a human may
    -- legitimately re-read a page years later to confirm it.
    CONSTRAINT fafsa_filing_deadlines_checked_in_or_after_cycle_check
        CHECK (checked_on >= make_date(award_year::INT - 2, 7, 1))
);

-- The natural key, and what the generated seed upserts on. It is a UNIQUE
-- INDEX and not an inline constraint because it needs NULLS NOT DISTINCT, and
-- that is how this repo already writes such a key -- cohort_money_stats
-- (0083:286-291) and cohort_population_counts (0085:165-171) both do it, for
-- the same reason: an axis whose NULL MEANS something ("the overall figure"
-- there, "federal" here) must collide with itself rather than land twice.
-- Without it, two disagreeing federal rows would both insert.
CREATE UNIQUE INDEX fafsa_filing_deadlines_natural_key
    ON fafsa_filing_deadlines (award_year, state, program, event)
    NULLS NOT DISTINCT;

COMMENT ON TABLE fafsa_filing_deadlines IS
    'The FAFSA dates for one award year (brief 0012 D4): the federal open date '
    'and deadline, and each state''s own deadlines for the aid the FAFSA '
    'unlocks. Hand-typed from each publisher''s page, cited per row, and '
    'expiring per row. One store for the public page and the coach, so the two '
    'cannot disagree.';

COMMENT ON COLUMN fafsa_filing_deadlines.state IS
    'The USPS code of the state whose agency published this, or NULL for the '
    'FEDERAL row. Not a foreign key: us_states is loaded by the codebook '
    'ingest and is empty in a freshly migrated database. Membership in the 59 '
    'published codes is UsStateCodes.parse''s job, in the generator and the '
    'seed test.';

COMMENT ON COLUMN fafsa_filing_deadlines.deadline_kind IS
    'date: a published day, in deadline_date. wording: a published RULE and no '
    'day ("as soon as possible", "until funds are exhausted"), carried as '
    'those words and never flattened into a date. none: we read the page and '
    'it publishes no deadline -- a verified absence with a citation, not our '
    'silence.';

COMMENT ON COLUMN fafsa_filing_deadlines.published_text IS
    'The publisher''s own words for this deadline, on every row whatever the '
    'kind -- including the hour and time zone a DATE cannot hold. The parsed '
    'deadline_date is OURS; this is theirs, and the page shows both.';

-- NO INDEX beyond the natural key, deliberately, and for the RFC 197 reason:
-- the only read is "every row of one award year" over ~60 rows, which reads
-- the whole table whatever indexes exist. The natural key LEADS with
-- award_year, so a by-year probe walks it (the 0085 rule).

CREATE TRIGGER trigger_03_enforce_fafsa_filing_deadlines_updated_at
BEFORE UPDATE ON fafsa_filing_deadlines
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
```

**Two things a reviewer should push on.** (1)
`make_date(award_year::INT - 2, ...)` casts a `SMALLINT` domain because
`make_date` takes `int`; the whole CHECK is unexecuted SQL. (2) The
cycle-containment CHECK is the one constraint here with **no direct precedent**
in the tree — it is cheap, it catches the year-shift typo at write time, and it
is the SQL half of §4's failure mode.

### 2.3 The enums, and where they live

`db/src/main/kotlin/ed/unicoach/db/models/`, one file each, the `AidForm.kt`
shape — `enum class X(val value: String)` with the `fromValue` companion
(`AidForm.kt:43-46`):

- **`AidDeadlineEvent`** — `FORM_OPENS("form_opens")`,
  `PRIORITY_DEADLINE("priority_deadline")`, `FINAL_DEADLINE("final_deadline")`.
  KDoc must say why publishers' words are NOT in this enum: fifty agencies name
  the same event fifty ways, and a caller branching on the code to fix its own
  wording should have read `published_text`.
- **`AidDeadlineKind`** — `DATE("date")`, `WORDING("wording")`, `NONE("none")`,
  with the KDoc carrying D4's reason: flattening "until funds are exhausted"
  into a plausible date would be indistinguishable from a fact on the page.

Both carry `BY_VALUE`/`fromValue` and a `VALUES` list (the `JurisdictionKind`
precedent). The row model then pairs kind with date so illegal states do not
typecheck — the `ScoreRelease` argument at
`AdmissionTestAdministration.kt:22-28`:

```kotlin
sealed interface PublishedDeadline {
  val publishedText: String
  data class OnDate(val date: LocalDate, override val publishedText: String) : PublishedDeadline
  data class InWords(override val publishedText: String) : PublishedDeadline
  data class NonePublished(override val publishedText: String) : PublishedDeadline
}

sealed interface DeadlineJurisdiction {
  data object Federal : DeadlineJurisdiction
  data class State(val uspsCode: String) : DeadlineJurisdiction
}
```

---

## 3. THE SEED GENERATOR

### 3.1 `bin/test-calendar-seed` (450 lines), read end to end

Preamble `:1-29` documents four decisions: no machine feed exists (`:6-9`); a
generated MIGRATION rather than an ingest phase because `bin/test` never ingests
(`:11-18`); the CSV is the source and the SQL an artifact (`:20-23`); validation
reports every fault in one pass (`:25-29`). `:30` sources `bin/common`; `:32`
`strip_git_hook_env`.

- **Grammar** `:68-84` — `while getopts ":c:d:nh" opt`, with
  `local OPTIND OPTARG opt` at `:69`; value-shape checks are separate, `:86-95`.
  Help is a `cat << 'EOF' … exit 0` heredoc declaring options AND exit codes
  (`:54-59`).
- **ORDER pre-flight** `:105-127` — `assert_order_is_appendable`, already lifted
  into `bin/common` (its comment records that a re-implemented `grep -Fxq`
  shipped a defect).
- **One awk pass validates then emits** `:152-368`: header gate `:181-191`;
  byte, quote and arity gates `:193-209`; the positional bind `:211-212`; **nine
  mirrored CHECK predicates `:214-267`, each tagged `# MIRRORS <constraint>`**;
  cross-row dedupe `:269-271` and cross-row work `:280-289`; END gates
  `:299-308`; SQL emission `:309-346` (table, column list,
  `ON CONFLICT (test, test_date)`, eight `EXCLUDED` assignments); `close(out)`
  check `:337-343`; stderr re-emit and status triage `:349-367`.
- **`main()`** `:370-448` — mktemp guards, three-answer `cmp -s`, ORDER append
  and rescan, and `-n`'s verdict `EXIT_SEED_WOULD_CHANGE` at `:444-446`.
- **Three external guards**: the Kotlin `EXPECTED_HEADER` tie
  (`AdmissionTestCalendarSeedTest.kt:181-196`), a DDL-vs-`MIRRORS` **set
  equality** in `bin/test-calendar-seed-scripts-tests:629-640`, and `-n` run
  against the real tree at `:654-659`.

### 3.2 What a FAFSA sibling copies, and what must differ

**Copies near-verbatim (~250 of 450 lines):** `common` + `strip_git_hook_env`
(`:30-32`), the getopts block and its four arms (`:68-84`), the five value-shape
checks (`:86-95`), `SCHEMA_DIR`/`ORDER_FILE` canonicalisation (`:97-100`),
`assert_order_is_appendable` (`:105-127`), the awk invocation shape
(`2>&1 > names`, `LC_ALL=C`, `:158-163`), `fail`/`is_iso_date`/`get_quoted`/
`get_date_or_null`/`get_text_or_null` (`:164-179`), header/byte/quote/arity
gates, the END gates, the stderr triage, and all of `main()`.

**Must differ:**

1. **Header constant + column bind** (`:132`, `:211-212`) change wholesale.
2. **Natural key** becomes `(award_year, state, program, event)`; the dedupe at
   `:269-271` rekeys, and the `ON CONFLICT` list at `:309-346` follows §2's
   `NULLS NOT DISTINCT` index.
3. **date|wording|none is a NEW either, and it is the one place copy-paste gives
   the WRONG rule.** The existing either is the score-release pair (`:252-254`),
   an _equality of emptiness_ (`(rel == "") != (surl == "")` fails). The FAFSA
   rule is value-iff-kind: a date present **iff** `kind == "date"`, wording text
   present on every row. Copying the citation predicate yields an inequality
   where an iff is wanted.
4. **`award_year`, not `cycle`** — the mod-100 cycle-format validator `:218-233`
   is DELETED, not adapted; the generated name derives from the year
   (`seed-fafsa-deadlines-2027.sql`, legal under `bin/common:137-141`).
5. **The ordering CHECKs have no counterpart.** `registration < test_date` etc.
   are facts about a sitting. The plausible FAFSA analogue — a state deadline at
   or after the cycle's open date — needs a cross-row pass over the FEDERAL rows
   (machinery exists at `:280-289`), and the bound itself is UNVERIFIED. **State
   the bound in the spec before coding it.**
6. **Scale and the floor.** `MINIMUM_AUTHORED_ROWS = 15`
   (`AdmissionTestCalendarSeedTest.kt:288`) becomes **set equality against the
   51 filing jurisdictions**, not a count: "51 rows" passes on a file that lists
   Texas 51 times and omits Wyoming.
7. **New work regardless**: a second `PROVENANCE.json`, a Kotlin fixture +
   committed-corpus test, a `bin/<name>-scripts-tests` harness (the calendar's
   is 661 lines, ~80% structural — the biggest single cost item), and a
   `bin/shell-tests` registration line (`:39`, `:91`).

### 3.3 Sibling, not generalisation — with the evidence

Classification of all 450 lines: **generic machinery 250 (~56%)**,
table-specific CODE 142, table-specific PROSE 58. But the table-specific 44% is
the hard part:

- The CSV column set is hard-coded **twice** — the literal `:132` and the
  positional bind `:211-212`.
- The 54 lines of mirrored predicates `:214-267` **cannot be data**:
  `if (reg >= td)`, `if (late <= reg || late > td)`,
  `substr(cycle,6,2) != sprintf("%02d",(substr(cycle,1,4)+1)%100)` are arbitrary
  predicates over arbitrary tuples. A descriptor expressive enough is a DSL. The
  FAFSA value-iff-kind rule is a predicate the calendar's grammar does not
  contain at all — direct evidence that predicates do not enumerate.
- The DDL↔mirror guard is table-scoped BY NAME
  (`bin/test-calendar-seed-scripts-tests:631-634` greps
  `CONSTRAINT admission_test_administrations_[a-z0-9_]+_check`), so generalising
  means **editing the guard that currently protects a landed, date-critical
  feature** to add a new one.
- Genuinely mechanical-from-a-descriptor: the SQL emitter, 38 lines.

**Recommendation: a sibling script.** Two instances is the wrong sample size to
abstract from; the shared part has stopped changing, and its known defects are
written down in the file a copier reads (`:158-160`, `:337-343`, `:379-385`,
`:400-409`). If Ian wants the duplication cut, the cheap safe subset is lifting
the ~35-line awk prelude into a shared fragment, leaving predicates and emitter
per script. _(UNVERIFIED that the repo has any shared-awk convention; none
found.)_

### 3.4 The `bin/` conventions it must obey

From CLAUDE.md (stated as invariants) and `bin/functions`:

1. **`getopts`, short options only** — no hand-rolled loops, no long options;
   value-shape checks live after the grammar. Model:
   `bin/test-calendar-seed:70`.
2. **All logging to stderr** — `bin/functions:232-257` (`log-info`,
   `log-warning`, `log-error`, `fatal`, the latter raising codes via its own
   `getopts ":s:"` `:246-253`). stdout carries only `-h` help or machine data; a
   generator whose product is a file writes **nothing** to stdout, and that
   emptiness is asserted (`bin/test-calendar-seed-scripts-tests:460-464`,
   `:657-658`).
3. **Reserved exit codes** (`bin/functions:1-10`, band 10-29):
   `EXIT_UNKNOWN_OPTION` 10, `EXIT_OPTION_REQUIRES_VALUE` 11,
   `EXIT_UNEXPECTED_ARG` 20, `EXIT_MISSING_REQUIRED_ARG` 21,
   `EXIT_INVALID_ARG_VALUE` 22, `EXIT_EXCLUSIVE_OPTIONS` 23. Operational codes
   this family also uses: `EXIT_POSTGRES_UNREACHABLE` 2 (`:47`),
   `EXIT_SEED_WOULD_CHANGE` 3 (`:60`, "read by ship-land as _regenerate and
   re-commit_"), `EXIT_SCHEMA_ORDER_DRIFT` 9 (`:53`).
4. Uniform across siblings: `source "$(dirname "$0")/common"` first line, and
   the same three closing checks on the option block (`\?` arm, `:` arm,
   `[ "$#" -gt 0 ]` → `EXIT_UNEXPECTED_ARG`).

### 3.5 How the seed reaches the test DB

There is **no seed loader**. The generated file is an ordinary migration:
`bin/test:141` → `bin/db-reset:38-40` (`db-drop -y`, `db-create`, `db-migrate`)
→ `bin/db-migrate:57` (`schema_pending_files "$DB_SCHEMA_DIR"`, refusing a
corpus that disagrees with itself before the first `BEGIN`) → `ORDER:107-108`.

---

## 4. STALENESS

### 4.1 The 90-day dated build failure, exactly

**Constant**,
`db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestAdministration.kt:94-109`:
`val CALENDAR_REFRESH_LEAD: Period = Period.ofDays(90)` — "ONE named home,
referenced by the staleness guard, by the KDoc above and by the DDL header
comment", a `Period` "so the unit is in the TYPE".

**Guard**, `db/src/test/.../AdmissionTestCalendarSeedTest.kt:210-238`, over the
committed CSV read **from disk**, not the database (`:19-22`):

```kotlin
val live = rows.filter { !it.testDate.isBefore(today) }
val stale =
  live
    .filter { ChronoUnit.DAYS.between(today, it.expiresOn) < CALENDAR_REFRESH_LEAD.days }
```

`ChronoUnit.DAYS.between` is signed, so an already-expired row also fails —
expiry and near-expiry are one condition. `today()` is New York (`:267`) because
`:db` cannot see `COACH_ZONE` (`:258-266`).

**Exemption and clearability** (`:202-209`, `:32-38`): only rows whose test date
is still ahead are the subject, because landed rows are never deleted, so
reading every row's expiry would make the bar **permanently unclearable** — and
appending, the action the failure message asks for, would not clear it. That
claim is executed, not asserted: `:90-110` runs the guard at `2027-08-01`,
appends a year-shifted cycle, and asserts it passes with landed rows unchanged.
Two more bars keep it non-vacuous: `MINIMUM_AUTHORED_ROWS = 15` over ALL rows
(`:275-288`) and `live.isNotEmpty()`.

**Read-time refusal**, `service/.../testing/TestTimelineService.kt:317-336`: a
sealed either `TestTimeline.NoCalendar(cycles, reason)` |
`TestTimeline.Resolved(asOf, CalendarWindow, …)`,
`NoCalendarReason = EXPIRED | EMPTY` (`:53-64`, because "'Our calendar is out of
date' would be a FALSE statement about a table with nothing in it"); the refusal
carries **no `asOf`** (`:235-239`) and `CalendarWindow.expiresOn` is **not
nullable** (`:34-39`: the old shape "let a stale date sit beside
`expired = false` and compile").

### 4.2 Why it does not transfer, and the clock trap

**(a) Deadlines recur.** A sitting happens once, so "test date passed" is a safe
exemption. Texas publishes 15 January every year. A passed deadline **inside the
current cycle is still load-bearing** — on 20 January 2027 the page must still
say Texas closed on 15 January 2027, the same point
`TestTimelineService.kt:255-260` makes about `closedDeadlines` ("the single most
decision-relevant fact this read computes for a late student"). So **the live
set is defined by AWARD YEAR, not by date**: a row is live iff
`award_year == the cycle now being filed`.

**(b) July 1 is the WRONG clock, and the wrong helper is already in the tree.**
`FederalAidPolicyService.kt:196-200`:

```kotlin
val startYear = if (today.month >= Month.JULY) today.year else today.year - 1
```

That is the award year _running now_ — right for Pell, wrong for deadlines. On
2026-11-01 it returns 2026, but the senior filing that day is filing the
**2027-28** form (public by 1 Oct 2026). **Reusing `currentAwardYear()` would
serve last cycle's deadlines for nine months of every year**, delivered by a
correct-looking helper. `AcademicYear`'s own KDoc anticipates this
(`common/.../AcademicYear.kt:32-36`).

### 4.3 The failure mode to avoid, named

> **A page that shows LAST CYCLE's date as if it were this cycle's.** Every row
> is present, unexpired, correctly formatted, correctly cited; only the award
> year is stale. Because the dates recur, nothing _looks_ wrong — no passed
> date, no empty cell, no dead link. It is the one rot invisible to every check
> the admission-test calendar performs. Near-miss variant: 40 states refreshed
> to 2027-28 and 11 left on 2026-27, so one page mixes two cycles.

### 4.4 The constants and the five bars

Constants in one home on the row model (the `CALENDAR_REFRESH_LEAD` shape):

| Constant                | Value                | Why                                                                                                                                                         |
| ----------------------- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FORM_OPENS_BY`         | `MonthDay.of(10, 1)` | statutory public-by date; the cycle's turnover                                                                                                              |
| `NEXT_CYCLE_GRACE`      | `Period.ofDays(30)`  | fires the cycle bar on 31 Oct, so a refresh has a month and is never asked for data that does not exist yet                                                 |
| `DEADLINE_REFRESH_LEAD` | `Period.ofDays(90)`  | the RFC 197 number, kept, for the RFC 197 reason                                                                                                            |
| `FILING_JURISDICTIONS`  | 50 states + DC = 51  | coverage floor; territories excluded because whether they publish FAFSA-linked deadlines is UNVERIFIED, and a bar over unsourceable data is permanently red |

Plus a **new clock**, `currentFilingYear(today)`, in `:db` beside the model so
the seed test and the service share one rule: the year that opened most
recently, so 2026-10-01 → 2027-09-30 all file the 2027-28 form. Its KDoc must
say explicitly that it is NOT `currentAwardYear()` and why.

**`FafsaDeadlineSeedTest` in `:db`**, over the committed CSV on disk (the
`AdmissionTestCalendarSeedTest.kt:40-44` reason: "a guard inside the artifact
under rewrite is not a guard"), five conditions folded into one
`guardFailures(rows, today)`:

1. **Cycle currency (NEW — the bar that catches §4.3).**
   `rows.maxOf { awardYear } >= currentFilingYear(today)`, relaxed inside
   `NEXT_CYCLE_GRACE`. The admission-test calendar has no equivalent: there, a
   spent cycle is caught by dates passing; here nothing passes.
2. **Coverage as membership, not a count.** For the latest award year, every one
   of the 51 jurisdictions has ≥1 row, and the federal jurisdiction has both a
   `form_opens` and a `final_deadline` row. Catches the half-typed refresh and
   the mixed-cycle page.
3. **Membership** — every `state` parses through `UsStateCodes.parse`.
4. **Expiry lead**, over rows of the latest year ONLY:
   `DAYS.between(today, expiresOn) < DEADLINE_REFRESH_LEAD.days`, signed exactly
   as today's. Superseded years are exempt — the "test date has passed"
   exemption relocated from the date axis to the award-year axis, which is what
   keeps the bar clearable by an append.
5. **The refresh path, executed** — guard fails on a clock past the committed
   cycle; append a year-shifted cycle; passes, landed rows byte-identical
   (`AdmissionTestCalendarSeedTest.kt:90-110`).

Plus the PROVENANCE manifest test (sha256, row count, years, `checked_on`,
`expires_on`), unchanged from `:123-168`.

**Authoring rule the spec must state**, or bar 4 becomes unclearable: set
`expires_on` to the cycle's federal final deadline, NOT to the next cycle's
opening. Bars 1 and 4 then have different jobs and different fixes — bar 1 wants
the NEXT cycle typed, bar 4 wants THIS cycle's pages re-read. This is exactly
the permanently-red-bar mistake `AdmissionTestCalendarSeedTest.kt:32-38` was
written to avoid.

### 4.5 The refusal shape

Three arms, because there are three genuinely different facts:

```kotlin
enum class NoDeadlinesReason {
  EMPTY,             // the table is empty; "out of date" would be false
  SUPERSEDED_CYCLE,  // rows are healthy and unexpired but belong to a cycle that is over
  EXPIRED,           // this cycle's rows, no longer believed
}

sealed interface FafsaDeadlines {
  data class NoDeadlines(val heldAwardYears: List<String>, val reason: NoDeadlinesReason) : FafsaDeadlines
  data class Resolved(
    val asOf: LocalDate,
    val window: DeadlineWindow,            // non-nullable: a window that exists is one we are inside
    val federal: List<FafsaDeadline>,
    val states: List<FafsaDeadline>,
    val missingJurisdictions: List<String>, // never a silent gap on a page claiming every state
  ) : FafsaDeadlines
}
```

`SUPERSEDED_CYCLE` is the arm with no analogue in `TestTimeline`, and it is the
one that exists solely because deadlines recur. On any `NoDeadlines`, the page
renders **no table at all** and points at studentaid.gov and the state agency;
the coach refuses to name a date, exactly as `TestTimelineService` refuses.

---

## 5. THE PAGE

### 5.1 The cited-claim layer already half exists (correction)

`public-web` DOES render a cited claim with a live link —
`CostReportPage.kt:1495-1505`:

```kotlin
private fun FlowContent.sourceLink(citation: CdsCitation) {
  val target = citation.linkTargetOrNull()
  if (target == null) { +citation.citedAs } else { a(href = target) { rel = "noreferrer"; +citation.citedAs } }
}
```

used at `:1223-1227` and `:1450-1453`. Its KDoc (`:1461-1494`) already decides
four of the five questions a FAFSA citation layer faces: link TEXT is the human
citation and never the url ("heterogeneous and long — 1,744 characters at its
worst in our own seed"); the target is the publisher's own file and
`linkTargetOrNull()` asserts the scheme and FAULTS rather than rendering
("`href` is where escaping does not save a logged-out parent"); a dead source
keeps the citation and drops only the anchor (RFC 200); `rel="noreferrer"` is on
the page even though the route sets the header.

**What is genuinely missing is the DATE half.** `CdsCitation.kt:30-45` carries a
cycle label, not an "as of". The primitive exists unrendered in `:db`
(`record-source-url-status.sql:48-54` `source_url_checked_on` with its `CHECK`,
and the calendar's `checked_on`/`expires_on`). So the FAFSA page builds the
**"as of"/expiry half** and should reuse `sourceLink`'s four decisions rather
than re-litigate them — as a PATTERN, since `sourceLink` is private and typed to
the CDS-specific `CdsCitation`. Note `CostReportPageTest.kt:1877-1892` counts
the literal `href` in ONE named file and asserts exactly 1; a new page is not
covered, so **the FAFSA page ships its own equivalent sweep** or the
one-anchor-helper rule silently does not apply to it.

### 5.2 What Layout + CSS allow for a 50-state table on a phone

- **Viewport is present** — `Layout.kt:37-50`
  (`meta(name = "viewport", content = "width=device-width, initial-scale=1")`),
  one stylesheet `/site.css` (`Routing.kt:150` `staticResources("/", "static")`,
  246 lines, pinned as `text/css` by `StaticAssetsTest.kt`).
- **`main { max-width: 760px; margin: 0 auto; padding: 2rem 1.5rem }`**
  (`site.css:49-53`), and `siteLayout` puts every body inside it
  (`Layout.kt:53`) — ≈712px usable on desktop, ≈327px on a 375px phone.
  `box-sizing: border-box` is global (`:11-13`).
- **The stacking table already exists, and its comment is brief 0012's problem
  verbatim** — `site.css:85-90`: "Wide screens get a real table; below the phone
  breakpoint every row stacks into a card per school, because the parent reading
  a link pasted into a message is on a phone and a five-column table there is
  unreadable."
- Desktop rules `site.css:125-148`; **the file's ONLY media query** is
  `@media (max-width: 640px)` at `:210-245`, where `.report-table` and its parts
  go `display: block`, `thead` is `display: none`, and the column header is
  restored beside the value by
  `.report-table .report-value[data-label]::before { content: attr(data-label) ": " }`.
  The Kotlin half is `CostReportPage.kt:1152-1160` (`valueCell` writes
  `data-label`), with `:188-194` recording the rule that each column label is
  one named constant printed twice, "because two hand-typed copies of one label
  is a header and a stacked card that can disagree about what a column is".
- Everything else in `site.css` is class-scoped, and a new class has **zero**
  styling until written (`VerifyEmailPage.kt:41` uses `section("verify-email")`,
  a string absent from `site.css`).

**Recommendation, using only kotlinx.html + one stylesheet:**

1. **Generalise the stack rule to a neutral `.data-table`**, keeping
   `.report-table` as a companion selector so RFC 155's page is byte-untouched.
   ~10 lines of CSS, zero new mechanisms; a second near-copy of the media query
   is how two stacked layouts drift.
2. **One table per region under an `h2`, not a Region column.** Below 640px the
   table is not a table — `thead` is `display: none`, so a Region column
   vanishes on the phone and returns only as `data-label` noise on every row. A
   heading is a heading at every width and gives the phone reader a scan anchor;
   it also keeps each table 4-12 rows, where the stacked layout is ~3 lines per
   row (51 states in one stack is ~150 lines of scroll with no landmarks).
3. **Three columns, no more**: State (`th(scope = ThScope.row)`, the
   `.report-summary-table` shape at `CostReportPage.kt:522-581` /
   `site.css:152-154`), Deadline (the date OR the publisher's wording), As of
   (`checked_on`). Every non-header cell carries `data-label` from the same
   constant its `<th>` prints. Four columns starts wrapping inside 712px,
   because a state rule is a sentence.
4. **Citation per region, not per row** (RFC 190's "claimant stated before the
   figures it covers", applied at `CostReportPage.kt:1194-1198`, `:1417-1423`).
   A url in a `<td>` is unreadable at 327px.
5. **Non-date states render as the wording, styled quieter** — reuse the
   `.report-blank` idea (`site.css:169-177`) so "until funds run out" never
   reads as a date and is never flattened into one (D4).
6. **Reach for nothing else.** `details`/`summary` exist in kotlinx-html 0.12.0
   but hide content from the page's own reader; the site has no JS, no second
   stylesheet, no component layer, and `render/INVARIANTS.md` requires every
   body to go through `siteLayout` with no raw-HTML seam.

### 5.3 What `LegalPagesTest` suggests

`LegalPagesTest.kt:14-33` is a full HTTP round trip through the real routing
table (`testApplication { application { testPublicWebModule() } }`), asserting
200 + `text/html`, a body marker ("proves the legal copy renders dynamically,
not a static file") and `class="site-header"` / `class="site-footer"` ("proves
it rendered through Layout"). Four transfers, one gap:

1. The FAFSA test gets route existence, status and content-type for free.
2. Assert on the CONSTANTS, not copies of them (`CostReportPage.kt:236-243`:
   otherwise "a reworded hint can leave an absence assertion passing
   vacuously"), or on the values the FAKE source was seeded with.
3. The chrome assertion is the template for a citation-layer assertion: emit a
   class only the citation renderer writes (`claim-source`, `claim-asof`) so
   "this claim rendered its citation" is structural, not copy-dependent. Section
   slicing is already precedented — `CostReportPageTest.kt:1767-1768`
   (`body.substringAfter("report-kinds").substringBefore("report-sources")`).
4. **Assert COUNTS: claims == "as of" dates == sources.** `contains` passes when
   one claim of twelve carries a citation. The cost report composes the expected
   anchor exactly (`CostReportPageTest.kt:106`) and counts `href` for a dead
   source (`:2102`).
5. The gap: `LegalPagesTest` is DB-free only because the legal pages hold no
   data. A data-bearing page test is DB-free only behind a port with a fake —
   §1.2 from the test side.

And the **staleness failure belongs in `:db`**, not here
(`AdmissionTestCalendarSeedTest.kt:151-166`). D7's "dated build failure" is that
test copied, not a `public-web` test.

### 5.4 No who-pays page exists — confirmed again

`grep -rniE "who.pays|who_pays|whoPays"` over
`public-web/ service/ db/ prompts/
rfc/` returns **zero hits in `public-web/`**;
every hit is the coach's loan sentence
(`db/schema/seed-coach-system-prompt-v35.sql:39`,
`SystemPromptCatalogTest.kt:918`, `ComparisonBasis.kt:190`). `render/` is
exactly
`CostReportPage, ErrorPages, HomePage, Layout, PrivacyPage, TermsPage,
VerifyEmailPage`;
`Routing.kt:114-151` registers `/`, `/terms`, `/privacy`, `/verify-email`,
`/report`, `/healthz`, static. **`beyond/09` has not landed; the FAFSA page is
first-mover for the citation layer (D2).**

---

## 6. COVERAGE RECONCILIATION

**Settled statically — no DB run was needed or spent.** Method: one seed file,
one loader, measured with `csv.DictReader` at `HEAD` and at each of the file's
three commits via `git show <sha>:db/seed/cds/aid_policy.csv`.

**One file, one writer.** `db/seed/cds/aid_policy.csv` is the only aid-policy
seed; the only writer of `aid_form_requirements` is `CanonicalMoneyDao.kt:313`,
called once from `CdsSeedLoader.kt:1116`, and
`CanonicalMoneyLoader.kt:351-353` + `:1621`
(`SOURCES_FILLED_ELSEWHERE = setOf(MoneySource.COMMON_DATA_SET)`) forbid a
second filler. **Seed rows and DB rows are the same population.**

**Today (`HEAD` = `6b7586cf`):** 3,496 data rows over 366 distinct `unit_id`;
`fafsa_required` = **219 rows = 219 distinct colleges** (216 `reported`, 3
`not_collected_by_us`). No college carries two FAFSA rows; every value is `true`
or empty (`bin/fetch-cds-seed:1740-1742` drops `False`: "REQUIRED or UNKNOWN,
never 'not required' (RFC 170, D5)"); `source_variable` is `H.801` in 219/219;
**no unit carries more than one `source_year`** (119 at 2025, 100 at 2024), and
`CdsSeedLoader.kt:1071-1077` writes one applicant group. So neither year nor
group splits the count.

**The three numbers are one measurement at three times against a corpus that
changed under us:**

| Commit                                             | Date       | FAFSA rows | `reported` | units in file |
| -------------------------------------------------- | ---------- | ---------- | ---------- | ------------- |
| `f69119b0` "RFC 170: need and forms"               | 2026-09-06 | 275        | **272**    | **338**       |
| `3924931d` "shape/07b"                             | 2026-09-08 | 224        | 221        | 377           |
| `6b7586cf` "refuse two more CDS filings (RFC 182)" | 2026-09-09 | **219**    | 216        | 366           |

- **272** (`product/STATUS.md:1687-1690`) is a _correct_ reading of the
  RFC-170-era file (338 units, FAFSA 272 reported of 275, CSS 73 of 77). It was
  simply never re-measured after two re-fetches.
- **278** (`rfc/170-need-and-forms.md:292-294`) matches **no committed state at
  all** — at RFC 170's own landing commit the file held 275/272. It is a
  pre-commit fetch, frozen by RFC immutability. RFC 170 is a historical
  document, not a coverage statement about today's seed.
- The shrink was **upstream re-extraction, not a rule change**: PROVENANCE
  `corpus.cds_fields_rows` moves 16,767 → 19,086 → 19,085 while the junk counter
  `aid_policy_flag_junk` barely moves (31 → 32 → 31); 52 colleges lost their
  FAFSA row at `3924931d` and 47 of them still have other rows. Worked example
  `unit_id=229814`: same three H2 values, same `source_year`, same `source_url`,
  and the H.801 tick simply gone.

**(b) The number the spec should state: 219** colleges whose own CDS filing
lists the FAFSA as required of first-year aid applicants (216 the school's own
tick, 3 cells we could not read). Denominator: **366 colleges carry a CDS
aid-policy filing** — not 417, which is a document count. **Do not restate 219
as a bar in a test or an RFC**: it moved twice in three days, the shrink guard
sits at 200 (`bin/fetch-cds-seed:454`), and `:447-448` says the measured numbers
are deliberately not restated because "they move with every fetch, and
PROVENANCE.json records what a run actually saw."

**(c) The honest sentence for the spec:**

> Today 219 of the 366 colleges that carry a Common Data Set filing list the
> FAFSA as required of first-year aid applicants (216 from the school's own
> tick, 3 cells we could not read). A college absent from that list is **not
> listed in its own filing** — which is not the same as the school saying the
> FAFSA is not required — and the figure moves with every corpus fetch, so it is
> measured at the time, never hardcoded.

For user-facing copy, do not invent a sentence: the approved one is byte-frozen
at `service/.../costs/AidPolicyWire.kt:231-234` and pinned by
`SystemPromptCatalogTest.kt:1999`.

---

## 7. PROMPT COLLISION

### 7.1 Brief 0011 is UNCOMMITTED

`product/0011-beyond-money/` is absent from this worktree because it is not on
`main`: in the root checkout, `git status --porcelain` shows
`?? product/0011-beyond-money/`. It is one session's working-tree file — it can
change under you and is invisible to every other worktree. **A 0012 spec citing
it must say which copy it read.** §7.2-7.4 are read from
`/Users/ian/Work/unicoach/product/0011-beyond-money/spec.md` (527 lines) and
that checkout's `product/STATUS.md`.

### 7.2 What is live

`STATUS.md:2877` — brief 0011: "GATES 1+2 APPROVED (2026-09-10/11); **NOTHING
LANDED YET** ... Nine slices `beyond/01`-`beyond/09`".

| Slice                                   | Touches the prompt?                                     | Evidence                    |
| --------------------------------------- | ------------------------------------------------------- | --------------------------- |
| `beyond/01/what-the-coach-may-not-say`  | **yes, mints a version**                                | `spec.md:81`, `:87`, `:102` |
| `beyond/05/the-major-answer`            | **yes**, and possibly `CollegeCostChatTool.DESCRIPTION` | `spec.md:236-237`           |
| `beyond/09/who-pays-for-college-advice` | no — the `public-web` page                              | `spec.md:414`, `:433-437`   |
| `beyond/02,03,04,06,07,08,10`           | no                                                      | grep: 0 hits per body       |

Confirmed by the spec itself (`:433-434`): "`beyond/01` and `beyond/05` both
mint a coach prompt version". Brief 0010's five unlanded slices (`test/04.5`,
`05`, `06`, `07`, `08`) name **no** prompt file or tool DESCRIPTION in their
bodies, though a prompt sentence is likely in practice — UNVERIFIED. Brief
0009's last slice `concise/07` is a render concern, deferred. **Net: both live
prompt contenders are ADDs; no deletion pass is live.**

### 7.3 The rule the 0012 spec must write down

The "one prompt-editing slice at a time" clause is **RETRACTED** — do not
re-import it. `product/0011-beyond-money/spec.md:485-494` (Ian, 2026-09-11):

> The clause "only one prompt-editing slice runs at a time" is **RETRACTED**. It
> was hazard 2 — a hold kept past its reason. RFC 185 made the prompt body a
> line-based Markdown file that **git merges like any other source**, and
> `bin/prompt-seed` generates the seed **at land, after the final rebase**, with
> one writer owning both the label and the pin. So parallel prompt work is fine.
> What remains: same-region edits are an ordinary rebase; **a deletion pass
> contends with every file it deletes from**; and git merges text, not meaning —
> the second lander reads the first's paragraph before adding a sibling rule.

So the 0012 rule, assembled from that plus
`product/0010-when-do-i-test/spec.md:78-112` and
`STATUS.md:3094-3097`/`:3009-3011`:

> A FAFSA prompt slice ADDS sentences, so it does **not** wait for `beyond/01`,
> `beyond/05` or any brief-0010 slice. Three obligations survive and are
> absolute: (1) **enumerate every file the pass touches and protect that set** —
> the one freeze that failed here failed through
> `CollegeCostChatTool.DESCRIPTION`, not the prompt file it covered; (2) **if
> the slice DELETES any prompt sentence it contends with every file it deletes
> from, including sibling slices**, and takes a real held window recorded in
> `product/STATUS.md` — `slice-board` cannot see a hold and will print the slice
> READY; (3) **git merges text, not meaning** — the second lander reads the
> first's paragraph before adding a sibling FAFSA rule, and puts any guard in a
> file its own pass does not open, because a guard inside the artifact under
> rewrite is not a guard.

### 7.4 Version v35, and how a new one is minted

- **Pin**: `service/src/main/resources/service.conf:314-315`
  (`systemPromptVersion = "v35"`, overridable by
  `COACHING_SYSTEM_PROMPT_VERSION`), read at `CoachingConfig.kt:40`, resolved
  per turn at `CoachingService.kt:812`.
- **Seed row**: `db/schema/seed-coach-system-prompt-v35.sql`, ordered by
  `db/schema/ORDER:111`, header `:1-15` "GENERATED FILE. DO NOT EDIT ... deleted
  and regenerated at land, after the final rebase". The catalog is insert-only
  (`0007.create-system-prompts.sql:88`, `:92`).
- **Minting is not yours to do.** `bin/prompt-seed` (1,128 lines) owns the label
  AND the pin (`:30-34`: "The label and the pin are one fact, so one writer
  writes both"), and mints **iff** the authored body differs from the catalog
  tip (`:36-44`, RFC 185 D6). The slice edits `prompts/coach-system-prompt.md`
  (223 lines) and `/ship` phase 6 runs `nix develop -c bin/prompt-seed`.
- **The pins that will fail you**:
  `service/src/test/.../SystemPromptCatalogTest.kt:658-677` asserts the served
  row equals the authored file byte-for-byte; `:799-820` asserts the pin is the
  catalog's highest version; and each rule paragraph is registered in
  `private enum class CoachParagraph` (`:214`, keyed by opening words
  `:240-243`) with its own `@Test` over `servedBody()` (e.g. `:1972`/`:1999`).
  **A new FAFSA paragraph must be registered in `CoachParagraph` and carries its
  own test.**
- Where a FAFSA rule lands today: prompt `:160` (federal_aid_policy routing),
  `:166` (borrowing + "what the FAFSA works out for them — the Student Aid
  Index"), `:194` (`required_forms` + the FAFSA sentence). The brief's other
  line references were written against an older prompt — re-measure, do not
  copy.

---

## Corrections to `research/repo-feasibility.md`

1. **"Nothing in `public-web` renders a cited claim" is false of the tree**
   (true only of the legal pages): `CostReportPage.kt:1495-1505` `sourceLink` is
   a working cited-claim renderer with four decided rules. Only the **"as of"
   date half** is missing (§5.1).
2. **`source_documents` is not merely the wrong precedent for deadlines, it is
   unusable** — `college_id NOT NULL`, a closed `money_source` domain, and
   tables rebuilt by the CDS ingest (§2.1b).
3. **The coverage question is settled: 219 / 366 today**, and 272 and 278 are
   dated measurements of a corpus that shrank upstream (§6).
4. Add: **`us_states` gives the region grouping for free**, and an FK to it
   would break the test DB (§2.1c); and **`currentAwardYear()` is the wrong
   clock for deadlines** (§4.2b).

---

## SLICE SHAPE (input to gate 2, not the decision)

Handle `fafsa`. Sizes: S ≈ a day, M ≈ 2-3 days, L ≈ a week of agent work.

| ID                                         | What it does, in one line                                                                                                                                                                                                                                                                                                  | Size  |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| `fafsa/01/the-deadline-table`              | Land `fafsa_filing_deadlines` (§2 DDL, Ian-approved at /ship's gate), the two enums + `PublishedDeadline`/`DeadlineJurisdiction` models, `FafsaDeadlinesDao`, and the `currentFilingYear` clock — no rows yet.                                                                                                             | **M** |
| `fafsa/02/fifty-one-deadlines-typed`       | The hand-typed `db/seed/fafsa-deadlines/deadlines.csv` + `PROVENANCE.json` for one award year (51 jurisdictions + federal, each cited and `checked_on`), the sibling generator `bin/fafsa-deadline-seed` (§3), its `-scripts-tests` harness, the generated seed migration, and `FafsaDeadlineSeedTest`'s five bars (§4.4). | **L** |
| `fafsa/03/one-answer-for-both-doors`       | `FafsaDeadlineService` in `:service`: award-year live set, expiry, the three-arm sealed refusal, `missingJurisdictions`, region grouping joined from `us_states`/`ipeds_regions` (§1.2, §4.5).                                                                                                                             | **M** |
| `fafsa/04/the-fafsa-page`                  | `GET /fafsa`: the explainer copy, the **cited-claim + "as of" rendering layer** (D2 first-mover), the `.data-table` generalisation and per-region tables (§5.2), D6's "the FAFSA is free at studentaid.gov" said early, and the count-asserting page test.                                                                 | **L** |
| `fafsa/05/the-coach-speaks-the-same-dates` | A chat tool over the SAME `FafsaDeadlineService` + prompt sentences routing deadline questions to it and forbidding a date from memory; refusal wording when the cycle is superseded or expired.                                                                                                                           | **M** |
| `fafsa/06/fafsa-process-answers`           | D3: cited tool-edge prose on the stable statutory rules (contributor + IRS consent, the divorced-parent >50%-support rule, no-SSN accounts, dependency), the D6 never-touch-the-form rule, and C4's `required_forms` surfaced harder — prompt + tool edge, **no DDL**.                                                     | **M** |

**`Needs:` edges I believe are real, with reasons**

- `fafsa/02` **BLOCKS-needs** `fafsa/01` — the generator emits
  `INSERT ... ON
  CONFLICT (award_year, state, program, event)` against columns
  that must exist, and its awk predicates mirror CHECK constraints by name
  (`bin/test-calendar-seed-scripts-tests:631-634` asserts set equality between
  the DDL's constraints and the script's `# MIRRORS` tags).
- `fafsa/03` **BLOCKS-needs** `fafsa/01` — it reads the DAO, the row model and
  `currentFilingYear`.
- `fafsa/03` **PREFER** `fafsa/02` — the service is testable on fixtures, but
  its `SUPERSEDED_CYCLE` and `EXPIRED` arms and the region grouping are only
  honestly exercised against real seeded rows; running after 02 avoids a second
  fixture corpus that can disagree with the CSV.
- `fafsa/04` **BLOCKS-needs** `fafsa/03` — the page renders exactly what the
  service returns and derives nothing itself (§1.2); with no service it would
  grow its own expiry rule, which is the disagreement D4 forbids.
- `fafsa/04` **CONFLICTS** `beyond/09/who-pays-for-college-advice` — D2: both
  build the cited-claim layer, and both touch `site.css` and the same region of
  `Routing.kt`/`render/`. First mover builds it, second inherits. Not an order.
- `fafsa/05` **BLOCKS-needs** `fafsa/03` — same service, same refusal arms.
- `fafsa/05` **CONFLICTS** `fafsa/06`, `beyond/01`, `beyond/05` — all ADD to
  `prompts/coach-system-prompt.md`. Per §7.3 this is an ordinary rebase, **not**
  a hold; the second lander reads the first's paragraph and registers its own
  `CoachParagraph` test.
- `fafsa/06` has **no data dependency** and can start immediately; it is the
  cheapest early value if the page slips.
- **No edge from anything to `fafsa/06` for C4**: `aid_form_requirements` is
  already landed (§6).

Two shape questions I deliberately leave to gate 2: whether `fafsa/02`'s typing
bill should be split by region into two slices (I think not — a half-typed cycle
fails bar 2 by design, so a split slice cannot land green), and whether the page
(`04`) or the process answers (`06`) go first given D1's stated order.
