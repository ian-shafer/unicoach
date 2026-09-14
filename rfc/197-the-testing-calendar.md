# RFC 197 — the testing calendar

**Slice:** `test/04/the-testing-calendar` (brief 0010,
`product/0010-when-do-i-test/spec.md`). **Lane:** A (rfc). New table, new
committed reference data, new chat tool, new coach prompt copy.

Ian's daughter asked "when do I need to take the SATs?" This is the slice that
answers her. Not the generic sequence — every competitor has that, and
CollegeVine's 2016 article still outranks all of them — but the subtraction
nobody does for one student: from her earliest real deadline, backwards through
score release and registration, to the date she must act.

For a 1 November early deadline the last comfortable sittings are **SAT 3
October** (scores 16 October) and **ACT 17 October** (scores 27 October), so the
date she must act is **mid-September**, when registration closes. Nothing in
unicoach can say that today.

## Detailed Design

### 1. The data is hand-typed, so it is committed, and it expires

`admission_test_administrations`, one row per (test, test date), seeded for the
2026-27 cycle: 8 SAT sittings and 7 ACT sittings, 15 rows.

Every date below was **re-verified against the publisher on 2026-09-11** by a
direct fetch of the three primary pages, not taken from the brief's research
note. The transcription was correct in every row it carried and incomplete in
three places, all three now recorded: the SAT's late-registration column is a
combined "Changes, Regular Cancellation, and Late Registration" deadline, not a
late-only date; College Board publishes score-release dates for **fall 2026
only**, so the three spring-2027 SAT sittings have no score release and the
column is NULL; and ACT publishes its own separate "Late Deadline" column.

| test | test date  | registration | late / changes | score release     |
| ---- | ---------- | ------------ | -------------- | ----------------- |
| SAT  | 2026-08-22 | 2026-08-07   | 2026-08-11     | 2026-09-04        |
| SAT  | 2026-09-12 | 2026-08-28   | 2026-09-01     | 2026-09-25        |
| SAT  | 2026-10-03 | 2026-09-18   | 2026-09-22     | 2026-10-16        |
| SAT  | 2026-11-07 | 2026-10-23   | 2026-10-27     | 2026-11-20        |
| SAT  | 2026-12-05 | 2026-11-20   | 2026-11-24     | 2026-12-18        |
| SAT  | 2027-03-06 | 2027-02-19   | 2027-02-23     | _(not published)_ |
| SAT  | 2027-05-01 | 2027-04-16   | 2027-04-20     | _(not published)_ |
| SAT  | 2027-06-05 | 2027-05-21   | 2027-05-25     | _(not published)_ |
| ACT  | 2026-09-19 | 2026-08-14   | 2026-09-01     | 2026-10-06        |
| ACT  | 2026-10-17 | 2026-09-11   | 2026-09-29     | 2026-10-27        |
| ACT  | 2026-12-12 | 2026-11-06   | 2026-11-29     | 2026-12-22        |
| ACT  | 2027-02-27 | 2027-01-22   | 2027-02-09     | 2027-03-16        |
| ACT  | 2027-04-10 | 2027-03-05   | 2027-03-23     | 2027-04-20        |
| ACT  | 2027-06-12 | 2027-05-07   | 2027-05-25     | 2027-06-23        |
| ACT  | 2027-07-10 | 2027-06-04   | 2027-06-22     | 2027-07-20        |

Sources, recorded per row:

- `https://satsuite.collegeboard.org/sat/dates-deadlines`
- `https://satsuite.collegeboard.org/scores/score-release-dates`
- `https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html`

Both publishers show a next-cycle list and both mark it provisional — College
Board "Anticipated SAT Weekend 2027-28 Test Dates", ACT "Projected 2027-2028
National Test Dates ... subject to change". **2027-28 is therefore not
committed.** That is what the expiry is for.

**The two publishers do not share a deadline timezone.** College Board: "SAT
Weekend deadlines expire at 11:59 p.m. ET, U.S." ACT: "Deadlines occur at 11:59
pm Central Time." D15 says the coach states dates and never hours, and never
says "closes today" without naming the publisher's timezone — so the timezone
label is a property of the test, carried on the Kotlin enum, not a column.

### 2. DDL

Two migrations, RFC 180 kebab slugs, both lines appended to `db/schema/ORDER`
and re-placed by `ship-order` at land.

`db/schema/create-admission-test-calendar.sql`:

```sql
-- ---------------------------------------------------------------------------
-- admission_test_administrations — the national SAT and ACT calendar.
--
-- Hand-maintained reference data (brief 0010 D19). There is no machine-readable
-- feed for this anywhere: College Board and ACT publish it as HTML tables and
-- nothing else. So every row is typed by a human from the publisher's own page,
-- committed as a CSV that a human reviews in a diff, and generated into a seed
-- migration from that CSV.
--
-- Every row carries its own expiry (D7/D20). Past the calendar's expiry the
-- coach REFUSES to name a date and points at the publisher; a test fails when
-- any committed row is within 90 days of its expiry or past it. Rot is the
-- competitor's failure mode and we find ours in CI, not from a family who
-- missed a date.
--
-- `test` is our OWN enumeration, so TEXT + CHECK IN (...) plus exactly one
-- Kotlin `enum class AdmissionTest(val value: String)` with `fromValue`
-- (CLAUDE.md schema conventions). `cycle` is ours too and is the academic year
-- the publisher labels the calendar with.
--
-- Derived dates are NOT stored (CLAUDE.md): the fee-waiver lead date is
-- `registration_deadline - 14 days` and is computed at read time and labelled
-- as derived. The "last usable sitting" for a deadline is likewise computed.
-- ---------------------------------------------------------------------------

CREATE TABLE admission_test_administrations (
    id                         UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    test                       TEXT        NOT NULL,
    cycle                      TEXT        NOT NULL,
    test_date                  DATE        NOT NULL,
    registration_deadline      DATE        NOT NULL,
    late_registration_deadline DATE        NULL,
    score_release_date         DATE        NULL,
    dates_source_url           TEXT        NOT NULL,
    score_release_source_url   TEXT        NULL,
    checked_on                 DATE        NOT NULL,
    expires_on                 DATE        NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (test, test_date),

    CONSTRAINT admission_test_administrations_test_check
        CHECK (test IN ('sat', 'act')),

    -- '2026-27': the academic year the publisher labels the calendar with.
    CONSTRAINT admission_test_administrations_cycle_format_check
        CHECK (cycle ~ '^[0-9]{4}-[0-9]{2}$'),

    -- Registration always closes before the test. A row that says otherwise is
    -- a typo, and a typo in this table is a family missing a deadline.
    CONSTRAINT admission_test_administrations_registration_before_test_check
        CHECK (registration_deadline < test_date),

    CONSTRAINT admission_test_administrations_late_deadline_check
        CHECK (late_registration_deadline IS NULL
               OR (late_registration_deadline > registration_deadline
                   AND late_registration_deadline <= test_date)),

    -- Scores are never released before the test, and an unannounced release is
    -- NULL rather than a guess: College Board has published fall 2026 only.
    CONSTRAINT admission_test_administrations_score_release_after_test_check
        CHECK (score_release_date IS NULL OR score_release_date > test_date),

    -- A score-release date and the page it was read from travel together: a
    -- citation with no fact under it is not data, and a fact with no citation
    -- is exactly what this brief exists to prevent.
    CONSTRAINT admission_test_administrations_score_release_citation_check
        CHECK ((score_release_date IS NULL) = (score_release_source_url IS NULL)),

    CONSTRAINT admission_test_administrations_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

CREATE INDEX admission_test_administrations_test_date_idx
    ON admission_test_administrations (test_date);

CREATE TRIGGER trigger_03_enforce_admission_test_administrations_updated_at
BEFORE UPDATE ON admission_test_administrations
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
```

`db/schema/seed-admission-test-calendar-2026-27.sql` — **generated** from the
committed CSV by `bin/test-calendar-seed`, never hand-edited. 15
`INSERT ... VALUES` rows, `ON CONFLICT (test, test_date) DO UPDATE`, all with
`cycle = '2026-27'`, `checked_on = DATE '2026-09-11'`,
`expires_on = DATE '2027-07-31'`.

`expires_on` is 2027-07-31: three weeks after the last sitting in the cycle
(ACT, 2027-07-10). D20's 90-day bar therefore fires on **2027-05-02**, by which
time both publishers have long since firmed up 2027-28.

No `FigureStatus`, no assurance tier: that vocabulary belongs to the canonical
money tables and a test date is not a money figure (spec preamble, standing).

### 3. Where the committed CSV lives, and how it reaches a database

`db/seed/test-calendar/administrations.csv` + `PROVENANCE.json`, committed,
following the `db/seed/cds/` layout: the header is asserted exactly and in order
before any write, an empty required cell is fatal, and `PROVENANCE.json` carries
`checked_on`, `source`, `rows` and `sha256` per file — the last two being the
keys the corpus tests actually assert. The CDS manifest's first key is
`fetched_at`; this one is `checked_on`, because nothing here was fetched: a
human read a page.

`bin/test-calendar-seed` reads that CSV and writes one
`db/schema/seed-admission-test-calendar-<cycle>.sql` per cycle present in it,
which is how next year's calendar arrives — a second cycle appended to the CSV,
never an edit to a landed seed. This is the `bin/prompt-seed` shape (RFC
181/185), not the CDS-loader shape, and the reason is stated here because it is
a deliberate departure from D19's "following the CDS seed pattern":

- The CDS pattern loads a committed CSV through the **ingest binary**. That
  leaves the rows absent from the test database (`bin/test` runs `db-reset` =
  drop/create/migrate and never ingests) and absent from any deployed database
  until a full college re-ingest runs. A **yearly, 30-row, hand-typed** refresh
  would then need a full ingest to take effect, and the committed file could sit
  ahead of the served data with nothing to say so. That is a second staleness
  gap, in the one slice whose entire point is that staleness is a build failure.
- Generating a seed migration from the CSV keeps every word of D19 — the data is
  a committed CSV with a `PROVENANCE.json`, hand-typed and reviewed in a diff —
  and additionally makes the rows present in **every** database the migrations
  touch, including the test database, with no re-ingest.

`bin/test-calendar-seed` follows `bin/` conventions: `getopts`, short options
only (`-c <csv>`, `-d <schema-dir>`, `-n` dry run, `-h`), all logging to stderr,
`bin/functions` exit codes, and **nothing on stdout** on a normal run because
its product is a file. It refuses a CSV whose header is not exactly the expected
header, a row that fails any of the DDL's CHECKs (it validates before it writes,
so a bad row is a review error and not a migration that fails at apply time),
and a duplicate `(test, test_date)`. Every one of those refusals, the empty
stdout, the `-n` exit 3 and the five usage exits are covered by
`bin/test-calendar-seed-scripts-tests`, on the `bin/prompt-seed-scripts-tests`
precedent and registered in `bin/shell-tests` — including the claim the script's
own comment makes, that a CSV with three faults reports all three in one run.

Unlike `bin/prompt-seed` this does **not** run at land: there is no version
label to compute at the tip, so the author runs it during implementation and
commits the CSV and the generated SQL together. `ship-order` places the two
`ORDER` lines as usual.

### 4. The read: a new chat tool

`admission_test_timeline`, a `StudentScopedChatTool`, registered in
`rest-server/.../Application.kt` beside the other eleven.

**It is a new tool, not a fifth section on `college_admissions_profile`.**
`CollegeAdmissionsService.readInSession` is asserted at exactly six statements
by `CollegeAdmissionsServiceTest`, on purpose; and the two answers are different
questions — "what does this school want" versus "what must I do, and by when".

Input: `putCollegeIdsSchema()`, so `null` means the student's whole active list,
and off-list ids come back as `unknown_college_ids` (the house contract).

Output:

```json
{
  "as_of": "2026-09-11",
  "as_of_zone": "Eastern Time",
  "year_of_school": "grade 11 (junior year)",
  "calendar": {
    "cycle": "2026-27",
    "expires_on": "2027-07-31",
    "expired": false
  },
  "anchor": {
    "college_id": "...",
    "name": "...",
    "round": "early_action",
    "round_label": "early action",
    "closing_as_published": "November 1",
    "closing_resolved": "2026-11-01",
    "resolution_basis": "the school publishes a month and day and no year; this is the November 1 of the application cycle for a student graduating in 2027",
    "citation": { "cited_as": "...", "url": "..." }
  },
  "sittings": [
    {
      "test": "sat",
      "test_name": "SAT",
      "test_date": "2026-10-03",
      "registration_deadline": "2026-09-18",
      "registration_deadline_time": "11:59 p.m. Eastern Time",
      "late_or_changes_deadline": "2026-09-22",
      "late_or_changes_deadline_label": "changes, regular cancellation and late registration",
      "fee_waiver_by": "2026-09-04",
      "fee_waiver_by_is_derived": true,
      "score_release_date": "2026-10-16",
      "registration_open": true,
      "usable_for_anchor": true,
      "why": "scores are released 2026-10-16, before the 2026-11-01 deadline"
    }
  ],
  "data_availability": ["score_release_date_for_sat_2027-03-06"],
  "general_sequence": null
}
```

Four rules the wire enforces, each of which is the honest version of a thing the
model would otherwise do from memory:

1. **`usable_for_anchor` is computed, never asserted.** A sitting is usable when
   its registration deadline is still ahead of `as_of` **and** its score-release
   date is on or before the anchor deadline. It is tri-state, and the order the
   three arms are tested in is itself a decision:

   - **A closed registration answers first, and answers `false`.** She cannot
     sit a test she can no longer register for, and that is a fact we hold. Its
     `why` names the registration deadline and today, and deliberately says
     **nothing** about scores, so a registration fact can never be read as a
     guess about score timing.
   - **Otherwise, a score release the publisher has not announced answers
     `null`**, with a `why` that says so. Never `true` and never `false` on that
     ground: we do not know when those scores land, and either verdict would be
     invented.
   - Otherwise the comparison is made and the answer is `true` or `false`.

   Review asked for the first two arms in the other order. They stay this way
   round because reporting `null` for a sitting she demonstrably cannot take
   hides a certainty behind an unknown, which is the opposite of what this slice
   is for. `registration_open` rides on the sitting so the reason is legible,
   and the unannounced score release still reaches `data_availability`
   independently — the two facts never mask each other.
2. **`fee_waiver_by` is derived** — `registration_deadline - 14 days`, College
   Board's own "ideally 1-2 weeks before the registration deadline" — and is
   flagged as derived so the coach says it as guidance, not as a published
   deadline. This is D14's fourth guardrail made into a date: the waiver is its
   own, earlier deadline.
3. **No list, no anchor.** `anchor: null` and `general_sequence` carries the
   standard fall-junior / spring-junior / fall-senior sequence, labelled
   general. The coach may still name real dates from the table, because the
   table is national.
4. **Past expiry, the tool returns dates for nothing.**
   `calendar.expired: true`, `sittings: []`, and a `refusal` string naming
   College Board and ACT. D7. The expired payload withholds `as_of`,
   `as_of_zone` and `calendar.expires_on` as well: they are dates, and a refusal
   with a date in it invites the model to compute from that date. The coach
   already knows today from the system prompt's own date block. An **empty**
   calendar answers identically, for the same reason — there is no honest date
   to give, and inventing one to fill the key is the defect this slice exists to
   prevent.

### 5. The year on a CDS deadline — the rule this RFC has to state

`college_deadlines` stores a month and a day and **no year**, and three places
in the tree say so and say we never render one: the schema header
(`0054:142-151`), `CdsMonthDay`'s KDoc, and
`CollegeAdmissionsChatTool.datePhrase` ("The cycle carries no year, so no year
is ever rendered into a date").

Deadline arithmetic cannot exist without a year. This RFC does not weaken that
rule; it states the one place a year may be attached and whose year it is:

> The school publishes no year. **We** supply one, from the student's own stored
> graduation year, and we say that is what we did. A student graduating in year
> _G_ applies in the academic year ending in _G_: a closing month of August
> through December resolves to _G-1_, January through July to _G_.

So the wire carries `closing_as_published` ("November 1") **and**
`closing_resolved` ("2026-11-01") **and** `resolution_basis` in words, and the
prompt tells the coach to say the resolved date as ours, not the school's. Where
the student's `HighSchoolYear` is `BEFORE_HIGH_SCHOOL` or `PAST_GRADUATION`
there is no application cycle to resolve into: `anchor` is null and the general
sequence is used. `CollegeAdmissionsChatTool` is untouched and its rule stands
unchanged for that tool.

### 6. `COACH_ZONE` is promoted

`COACH_ZONE` and `COACH_ZONE_LABEL` are private to `CoachingService`'s
companion, and D15 is one decision, not two. They move to a new
`service/src/main/kotlin/ed/unicoach/coaching/CoachClock.kt` —
`COACH_ZONE = ZoneId.of("America/New_York")`, `COACH_ZONE_LABEL` derived from it
exactly as today, and
`fun InstantSource.coachToday(): LocalDate =
LocalDate.ofInstant(instant(), COACH_ZONE)`.
`CoachingService.today()` becomes a call to it and nothing else changes there.

The new service takes `clock: InstantSource = InstantSource.system()` and calls
`clock.coachToday()`. **`LocalDate.now(clock)` appears nowhere**: it reads the
injected clock's zone, which is how RFC 186's first attempt passed for the wrong
reason.

### 7. Prompt copy — one new paragraph, and one correction

A new paragraph of fourteen sentences in `prompts/coach-system-prompt.md`, after
the testing paragraph (which ends at line 67 of the base tree), one sentence per
line, naming `AdmissionTestTimelineChatTool.TOOL_NAME` in the pinning test
rather than a literal. It carries the tool, the year-resolution rule of §5, the
unannounced-score-release rule, the general sequence for an empty list, the
expiry refusal, the publishers' differing deadline hours, and D14's four
guardrails — plus one sentence D14 does not name: that ACT runs a **separate**
fee-waiver programme with its own rules and fees, because D13 makes the two
tests symmetric and stating College Board's waiver alone would make the SAT the
default.

D14's second guardrail needs a correction, and this is RFC 187's lesson applied
to my own copy rather than discovered by review. D14 says superscoring is
"stated only from a recorded one". **We hold no per-school superscore policy
anywhere in this repo**, and instructing the coach to state one from a recorded
policy instructs a claim no tool can source. The copy therefore says what is
true: we do not hold any school's superscore rule, so say you would have to
check that school's own page, and say that it can differ for admission and for
scholarships. Same reasoning for revocation risk: "some colleges reserve the
right to withdraw an offer if official scores disagree" is general and true;
naming Stanford would be a per-school claim from memory.

The correction `test/03` could not make, recorded as its RFC 195 decision D-E:
line 61 currently ends "...scholarship score rules, athletics rules and score
ranges are not things we hold", which becomes false when RFC 195 lands score
bands. **RFC 195 has not landed at the time of writing** (its run is in
`designing`), so the replacement sentence it recorded is false _today_ and true
_after_ it lands. This run therefore resolves it at the final rebase, from the
tree rather than from a guess: if `AdmissionsField` carries `SCORE_BANDS` on the
rebased tree, apply RFC 195 D-E verbatim; if it does not, leave line 61 and say
so in the report, because writing "score ranges we do hold" while nothing holds
them is the exact defect this brief exists to prevent.

The prompt version label is claimed at land by `bin/prompt-seed` and is not
written here. Live is v31 at `BASE_SHA`.

Two words of the paragraph are not the ones first drafted.
`SystemPromptCatalogTest.assertParagraphKeepsTheStandingCopyRules` requires
every `subtract` in the prompt to be immediately preceded by `never`, which is
brief 0003's money rule, so the copy says "works the dates back" and "show them
the working" instead. The rule is right and the sentence is no worse; widening
it would be a `/principle` call, not this run's.

## Files Modified

**New**

- `db/schema/create-admission-test-calendar.sql` — the DDL above.
- `db/schema/seed-admission-test-calendar-2026-27.sql` — generated, 15 rows.
- `db/seed/test-calendar/administrations.csv` — committed, hand-typed, 15 rows.
- `db/seed/test-calendar/PROVENANCE.json` — `fetched_at`, `source`, `rows`,
  `sha256`.
- `bin/test-calendar-seed` — CSV -> seed migration generator.
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTest.kt` —
  `enum class
  AdmissionTest(val value: String)`, `SAT("sat")` / `ACT("act")`,
  `fromValue`, plus the publisher-specific words: display name, deadline
  timezone label, late deadline label, registration page url.
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestAdministration.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestCalendarDao.kt` — one read
  (`list`, the whole table in one statement: fifteen rows a cycle, and a cycle
  filter would answer a question about today that the DAO does not hold) and one
  upsert for the fixtures.
- `db/src/testFixtures/kotlin/.../AdmissionTestCalendarFixture.kt` — seeds rows
  for suites that truncate.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachClock.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/TestTimelineService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/testing/AdmissionTestTimelineChatTool.kt`

**Changed**

- `db/schema/ORDER` — two lines.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — the two
  zone constants move out; `today()` delegates.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — register the
  twelfth tool.
- `prompts/coach-system-prompt.md` — one new paragraph; line 61 conditionally.
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` —
  one `CoachParagraph` member, one accessor, the new @Tests.

## Implementation Plan

1. `AdmissionTest` enum + `AdmissionTestAdministration` model.
2. The DDL migration and its `ORDER` line; prove the table applies.
3. The committed CSV + `PROVENANCE.json`, typed from the verified table above.
4. `bin/test-calendar-seed`; run it; commit the generated seed and its `ORDER`
   line. Assert the script writes nothing to stdout.
5. `AdmissionTestCalendarDao` + testFixtures seeder.
6. `CoachClock.kt`; move the constants; `CoachingService.today()` delegates.
7. `TestTimelineService`: read the calendar, read the student's list deadlines,
   resolve the cycle year, compute `usable_for_anchor` and `fee_waiver_by`,
   handle expiry.
8. `AdmissionTestTimelineChatTool`; register it.
9. Prompt paragraph + `CoachParagraph` + catalog tests.
10. The staleness test (below) — written last so it is written against the
    committed file, not against an intention.

## Tests

**D20 — staleness is a build failure.** A test that reads
`db/seed/test-calendar/administrations.csv` **from disk** and fails when any row
is within 90 days of its `expires_on` or past it. It is its own committed-corpus
test class in `:db` — which owns `db/seed/test-calendar/` and the fixture —
modelled on the CDS corpus tests in `CdsSeedLoaderTest`, and **not** in the
tool's own test file: a guard inside the artifact under rewrite is not a guard
(spec, "the unit of contention" section). It carries a non-vacuity floor
(`rows.size >= 15`) so an emptied CSV fails rather than passes, and its failure
message names the two publisher pages and `bin/test-calendar-seed`. A second
corpus test recomputes `sha256` and the row count against `PROVENANCE.json`.

**D7 — the expiry refusal, executed.** A test drives an `InstantSource` fixed
past `expires_on` and asserts the tool returns no dates, `expired: true`, and
the refusal naming College Board and ACT. A sibling test one day _before_ expiry
asserts dates still come back, so the first test cannot pass by being broken.

**D14 — four guardrails, one executed test each**, against the prompt paragraph
via `SystemPromptCatalogTest`: (1) Score Choice is per test date and does not
cover the free sends chosen at registration; (2) no school's superscore rule is
held, and it can differ for scholarships; (3) self-reported scores carry
revocation risk; (4) the fee waiver is one per lifetime, carries unlimited free
sends, and is its own earlier deadline. Plus two tests for the date itself: the
service asserts `fee_waiver_by` is exactly 14 days before the registration
deadline, and the tool asserts the wire flags it derived — the flag is a
rendering decision and belongs where rendering is tested.

**The subtraction, end to end.** A junior with one school whose `early_action`
round closes 1 November: assert the anchor resolves to 2026-11-01, that SAT
2026-10-03 and ACT 2026-10-17 come back `usable_for_anchor: true`, that SAT
2026-11-07 comes back `false` with a `why` naming its 2026-11-20 score release,
and that the earliest usable **SAT** registration deadline is 2026-09-18 — the
mid-September action date this brief was opened to produce. The earliest usable
registration deadline of **either** test is asserted too, and on this clock it
is the ACT's 2026-09-11, closing that very day. Both are asserted by value:
naming only the SAT would make the SAT the default (D13), and naming only the
overall minimum would not answer the question as it was asked.

**A closed registration is a fact, not an unknown.** A test pins the clock to
2027-04-20, after the SAT 2027-05-01 sitting's 2027-04-16 registration deadline
and before the calendar's expiry, and asserts that sitting is
`usable_for_anchor: false`, that its `why` names 2027-04-16, and that its `why`
says nothing about a score release. A tool-suite test asserts the same sitting
reaches the wire as `false` **and** still appears in `data_availability` for its
unannounced score release, so neither fact masks the other.

**No date from memory.** The tool's test asserts every date in the payload is a
row in the table or a documented derivation, and the prompt paragraph is
asserted to forbid naming a test date the tool did not return.

**D13 symmetry.** A service test and a wire test both assert that neither
publisher is privileged: both tests appear whenever both have a usable sitting,
and the `sittings` array is ordered by test date and by nothing else. The prompt
paragraph is asserted to name them together.

**The spring-2027 gap.** A test asserts the three SAT sittings with no published
score release come back `usable_for_anchor: null` and appear in
`data_availability`, never `true`.

**`HighSchoolYear` edges.** `BEFORE_HIGH_SCHOOL` and `PAST_GRADUATION` yield
`anchor: null` and the general sequence. Each also sweeps the whole rendered
result for the cycle year that WOULD have been invented, so a year leaking into
the general-sequence text or a `why` fails rather than passing quietly.

**The zone.** A test pins an instant at 02:30Z and asserts the tool's `as_of` is
the previous US day — the assertion RFC 186 got wrong the first time.

**The generator.** `bin/test-calendar-seed-scripts-tests`, on the
`bin/prompt-seed-scripts-tests` precedent and registered in `bin/shell-tests`,
covers `-h` on stdout, an empty stdout on a normal run, the five usage exits,
the header refusal, every CHECK mirror by line number, the duplicate key, the
three-faults-in-one-run claim the script's own comment makes, that a refused CSV
writes nothing at all, `-n` exiting 0 and 3, and idempotence including the
`ORDER` line being appended exactly once.

Executed counts are reported after `nix develop -c bin/test`, from the JUnit XML
`tests=` attribute. No count is claimed in this document.
