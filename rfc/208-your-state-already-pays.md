# RFC 208 — your state already pays

**Slice:** `test/05/your-state-already-pays` (brief 0010,
`product/0010-when-do-i-test/spec.md`). **Lane:** A — new table, new generated
seed, new hand-typed corpus, new coach copy. **Base:** `main@b6966d4b`.

## Summary

For a large share of US students the first admission-test sitting is already
scheduled, inside the school day, and paid for by the state. unicoach already
holds the one field that says which students those are —
`money_profiles.residency_state` — and already renders it into the coach's
context for the in-state price. Today the coach can still tell such a family to
register for a Saturday SAT and pay for it. That is the trust-losing answer this
slice removes.

This RFC adds:

1. `admission_test_state_programmes` — append-only history, one row per
   jurisdiction per school year, keyed into the `us_states` codebook table:
   whether the state requires or offers a school-day admission test, who chooses
   which test, which test where the state chose one, the grade or grades,
   whether the state pays, the page it was read from and any archive capture of
   it, whose school year the row is for, and when the reading stops being
   believed.
2. `db/seed/test-state-programmes/programmes.csv` + `PROVENANCE.json`, the
   authored corpus (brief 0010 D19), and `bin/test-state-programme-seed`, which
   validates it and generates one seed migration per reading session (the RFC
   197 pattern, explicitly not the Common Data Set pattern).
3. A `school_day_programme` section on the existing `admission_test_timeline`
   tool, inferred from the stored residency state, plus the coach rules that
   govern it.

It changes no existing answer and asks the student for nothing.

## The amendment this design was rewritten under

Presented at the approval gate on 2026-09-14 and **amended by Ian**, in five
parts. They are recorded here because two of them are the same mistake — a
component this repo already had, reinvented locally — and that is worth being
able to find again.

1. **`state` is a foreign key into `us_states`** (0060), not a local
   `^[A-Z]{2}$` CHECK. _"Is `state` an FK into our own table? It should be."_
   The design's claim that the repo has no state vocabulary was half a fact and
   half a missed search: there is no state ENUM, but there is a published
   codebook table with 59 rows and a `jurisdiction_kind`. **The key could not be
   landed, for a reason the verify run found and section 9 records; Ian took the
   format rule plus a named follow-up.**
2. **A school year is stored as its FIRST year under the `academic_year`
   domain** (0087, RFC 170 D14), not as a `'2026-27'` string with a regex. _"A
   cycle is a school year that we already decided to store as only the first
   year."_ The regex CHECK and its generator mirror are **deleted, not ported**;
   0087 exists precisely so that "the second half is the first plus one" is not
   a rule anyone has to type twice.
3. **Prefer https; do not block http.** _"`https only` — why? We should prefer
   https, but not block insecure."_ A state page on plain http is still the
   state's page, and dropping a true row over the scheme is the wrong trade.
4. **Append-only history, not one replaceable row per state.**
   _"`One row per
   state, replaced yearly` is an anti-pattern because it makes
   data depend on outside processes. Data should be able to stand on its own and
   not lose value or correctness over time."_ This reverses the design's own
   decision 3. The staleness guard is redesigned rather than ported (section 8).
5. The full schema is presented whole at the gate, not abridged.

Two follow-ups fall out of it, and **neither is done here**:

- `money_profiles.residency_state` is the column that feeds this lookup and
  still carries a regex and no key. It should reference `us_states`. That is a
  money-layer migration with its own blast radius.
- `admission_test_administrations`, which RFC 197 landed on 2026-09-13, carries
  **both** defects Ian named: `cycle TEXT` with the `'2026-27'` regex plus
  arithmetic instead of the `academic_year` domain, and an `^https://` CHECK on
  both of its URL columns. They are named here as landed inconsistencies and
  left alone: correcting a landed table is its own slice, and doing it inside
  this one would put a migration nobody gated in the same commit as a new
  feature.

## Approved, with two additions (2026-09-14)

Ian approved the revised schema — _"`test/05's table` approve"_ — together with
two additions this design proposed after the second research pass found facts
the table could not hold:

- **`school_must_offer`**, one nullable boolean: the state requires every public
  high school to HOLD the sitting. Virginia's law does exactly that and then
  says no student must participate, so `participation` alone reduced the
  strongest fact in the corpus to "offered".
- **A second official document** — proposed, built, and then **dropped before
  landing**. See below: it is becoming a general model instead.

**The second-document question, and where it ended up.** The instruction was to
reuse `source_documents` (0087) if that was clean. It is not: its `college_id`
is `NOT NULL REFERENCES colleges(id)`, and its `source` column is the
`money_source` DOMAIN — `ipeds_sfa`, `ipeds_ic_ay`, `scorecard`,
`common_data_set` — mirrored by the Kotlin `MoneySource` enum, pinned to it by
test, and rendered through `MoneySourceCopy`'s exhaustive `when`s. A state
education agency is not a money source, and widening that domain would push a
non-money publisher through every one of those branches.

So this slice built the stated fallback, a per-corpus child table — and Ian then
ruled the question bigger than the fallback: _"We have the need to store links
to external documents. There are also archives... Let's model this correctly in
a separate table: `external_documents`. There will be a new table that maps
multiple sources (eg for Utah) between these two tables. We'll need to do a
cleanup slice to move all schema that points to external documents to use this
new table."_

The child table is therefore **deleted from this slice** — the repo already
answers this question privately in at least eight places (the three CDS tables,
`policy_parameters`, the canonical money tables, `source_documents` itself, the
test calendar, and this one), and a ninth private answer immediately before a
public one is the wrong direction. The programme row keeps its own `source_url`
and `archive_url`, which the cleanup slice will migrate with everyone else's.

**What that costs, per state, decided per state and not averaged.** A row ships
only when the ONE page it cites supports the claims that row makes. Delaware's
does: its state testing calendar is titled "State-mandated Testing Calendar" and
lists SAT School Day at grade 11, which is the requirement, the test and the
grade — and its row claims nothing about funding, so nothing on it is unsourced.
Utah's does not: the statute (53E-4-305) carries the requirement and the grade
but names the assessment only generically, and the board rule that names the ACT
carries neither. Utah is therefore **held out of the corpus** until either a
single sufficient page is found or `external_documents` lands. 35 states shipped
honestly beats 36 with a row whose citation does not support its claim.

This is the third component in one design that the repo already had — after
`us_states` and `academic_year` — and the first of the three where the existing
component genuinely did not fit. The pattern is worth naming: search first, then
judge, record the judgement either way, and be ready for the answer to be
"neither: this needs a model of its own, later".

## Decisions this RFC takes, and what governs them

- **D10 (brief 0010) — infer from the state, never ask for it again.** The read
  starts from `money_profiles.residency_state` and its `AnswerStatus` tri-state.
  Where the state is unanswered or declined the section says so, and the coach
  is told not to ask here; the residency invitation already exists in the cost
  conversation and nothing waits on it.
- **The state answers the state's question only.** No row, no wire field and no
  sentence says anything about one named school. The single permitted question
  is the school-level one — does your school run it, and when — it gates
  nothing, and "I don't know" is accepted in full.
- **D13 — the whole test family.** `test` is `sat` or `act` with neither
  defaulted, the copy names both wherever it names one, and a state that chose
  neither says so instead of being written down as one of them.
- **D7/D20 — staleness is a build failure**, redesigned for an append-only
  corpus in section 8.
- **D21 — one date seam, no new date pattern.** This RFC adds **no** date
  formatter and no new date wording. Every date it puts on the wire is an ISO
  `yyyy-mm-dd` string, as RFC 197's tool already does, and the school year is
  spoken through `AcademicYear`'s existing label, which is the one home for
  `2026-27`. The human-facing date renderings this RFC introduces are exactly
  two, both ISO strings: `school_day_programme.checked_on` and `.expires_on`.
- **D22 — no calendar pressure, no single user.** No copy says a real-world date
  is near. The executed tests carry a junior in a state that mandates the test,
  a senior past the programme grade, a student with no money profile, a student
  with an empty college list, a student past graduation, a state where the
  district chooses, a state where the student chooses, and a state we hold no
  row for.
- **D9 — no prep, no prediction, no odds.** Standing; nothing here approaches
  it.

## Detailed Design

### 1. The table

Presented in full at the approval gate (brief 0001 D10).

```sql
-- admission_test_state_programmes -- the state-run, school-day SAT and ACT
-- programmes (RFC 208, slice test/05/your-state-already-pays).
--
-- One row per US jurisdiction PER SCHOOL YEAR we have read a state source for:
-- whether the state requires or merely offers an admission test inside the
-- school day, who chooses WHICH test, which test that is where the state chose
-- it, the grade or grades it happens in, whether the state pays, the state page
-- it was read from, and the day a human read it. For a large share of students
-- the first sitting is already scheduled, in school, and free; telling that
-- family to pay for a Saturday is the trust-losing answer this table exists to
-- prevent.
--
-- IT IS A FACT ABOUT THE STATE AND NEVER ABOUT THE SCHOOL. Whether one named
-- high school runs its test day, and on what date, is not in here and cannot be
-- inferred from here: that is the single school-level question the coach may ask
-- (brief 0010 D10), it gates nothing, and "I don't know" is a complete answer.
--
-- IT IS APPEND-ONLY HISTORY, AND THAT IS THE LOAD-BEARING DECISION (Ian,
-- 2026-09-14: "one row per state, replaced yearly is an anti-pattern because it
-- makes data depend on outside processes; data should be able to stand on its
-- own and not lose value or correctness over time"). A re-read ADDS a row for
-- the new school year. Nothing is overwritten, so last year's answer stays true
-- about last year, and the corpus does not silently become wrong the moment
-- somebody stops running a script. Illinois moved from the SAT to the ACT and
-- Kentucky from the ACT to the SAT within two cycles, and both of those facts
-- are worth keeping rather than erasing.
--
-- THE READ TAKES THE NEWEST ROW PER STATE, and so does the staleness guard.
-- `DISTINCT ON (state) ... ORDER BY state, school_year DESC, checked_on DESC`.
-- Newest-per-state is what makes the guard both firing and clearable: a state
-- whose newest row is inside HandTypedReferenceData.REFRESH_LEAD of that row's
-- expires_on fails the build, and APPENDING this year's row for that state --
-- the one action the failure message asks for -- clears it. A minimum over ALL
-- rows would be the unclearable bar RFC 197's review caught, because in an
-- append-only corpus last year's row keeps its own expiry forever. And a
-- corpus-wide "some row is fresh" bar would let a state nobody re-checks hide
-- behind a fresher neighbour; per-state, it cannot.
--
-- EVERY COLUMN'S NULLABILITY WAS SETTLED BY READING THE PAGES, NOT BY DESIGNING
-- FIRST. Of the 31 jurisdictions actually sourced, most pages name no school
-- year at all and only 9 say who pays. A NOT NULL column there would have forced
-- either dropping a true row or inventing the missing half, and inventing a year
-- on a page that names none is the defect this brief exists to stop. So who pays
-- may be absent, and a school year the page did not state is recorded as OURS --
-- see school_year_basis.
--
-- HAND-MAINTAINED REFERENCE DATA (brief 0010 D19), on the RFC 197 pattern and
-- deliberately NOT the Common Data Set pattern: the CDS corpus loads through the
-- ingest binary, `bin/test` never ingests, and rows would therefore be absent
-- from every test database until a full re-ingest. So the authored source is
-- db/seed/test-state-programmes/programmes.csv, reviewable in a diff, and the
-- seed migration is GENERATED from it by bin/test-state-programme-seed, one seed
-- per reading session. Never hand-edit a generated seed; add rows to the CSV and
-- re-run the script.
--
-- ABSENCE MEANS WE DID NOT READ A SOURCE, NOT THAT THE STATE RUNS NOTHING.
-- There is no 'none' participation and no row asserting one. A confident "your
-- state runs no in-school test" is the same trust loss in the other direction
-- and needs a primary page saying so, which state assessment pages mostly do
-- not. A state with no row is reported as a gap in our data, in those words.
--
-- Reference-table shape (0060/0077 precedent, and its sibling
-- admission_test_administrations): unversioned, no soft delete, no OCC,
-- created_at/updated_at with the plain update_colleges_timestamp() trigger.
--
-- NO FigureStatus AND NO ASSURANCE TIER, for the reason RFC 197 states: that
-- vocabulary belongs to the canonical money tables and would claim a provenance
-- model this data does not have.

CREATE TABLE admission_test_state_programmes (
    id                UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- A FORMAT RULE TODAY, AND A FOREIGN KEY AS SOON AS IT CAN BE ONE.
    --
    -- The repo publishes the vocabulary this column should key into: `us_states`
    -- (0060) -- usps_code TEXT PRIMARY KEY, 59 rows, with a jurisdiction_kind
    -- that already tells a state from DC, a territory and a freely-associated
    -- state. Ian asked for the key explicitly, and he was right: 'ZZ' passes a
    -- regex and is nobody's jurisdiction.
    --
    -- It is not here because of WHEN that table is filled. `us_states` is
    -- CREATED by a migration and POPULATED by the data-import run, so on a
    -- freshly migrated database it is EMPTY -- and this corpus is seeded by
    -- migration, precisely so that `bin/test`, which never imports, sees the
    -- rows. The key was therefore refused at apply time with
    -- `Key (state)=(AL) is not present in table "us_states"`, and not only in a
    -- test database: a fresh production database migrates before it imports and
    -- would refuse it the same way. The two landed tables that DO key into
    -- `us_states` (0067: colleges, college_search_index) never meet this,
    -- because their rows also arrive at import time, after the codebook.
    --
    -- Ian named the real defect rather than the workaround: "Unicoach owns the
    -- us_states table, so it should populate it." `test/05.3/own-our-codebooks`
    -- carries that fix -- it seeds the published codebooks at MIGRATION time
    -- from their authored files, the same shape this corpus already uses, and
    -- then adds the keys that could not exist before, this one included. The
    -- codebook has one writer either way; what changes is WHEN it is written.
    --
    -- Until then the shape is checked here and the MEMBERSHIP is checked in the
    -- committed file: AdmissionTestStateProgrammeSeedTest reads every state code
    -- against db/data/codebooks.json, so a typo still fails the build -- in the
    -- file, where the fix is, rather than at apply time in somebody's db-reset.
    -- The regex is the same one money_profiles.residency_state carries, which is
    -- the only column that ever supplies this lookup.
    state             TEXT          NOT NULL,

    -- The school year this row describes, under the `academic_year` domain
    -- (0087, RFC 170 D14): a SMALLINT START year. 2026 IS the 2026-27 school
    -- year, and the '2026-27' label is rendered at read time by AcademicYear in
    -- :common and never stored.
    --
    -- The first draft stored the LABEL as TEXT with a '^[0-9]{4}-[0-9]{2}$'
    -- CHECK plus arithmetic, and mirrored that rule into the generator's
    -- validator -- one rule typed twice, in two languages, which 0087's own
    -- comment exists to stop: "a regex cannot say the second half is the first
    -- plus one. A SMALLINT start year cannot spell that mistake at all." Both
    -- the CHECK and its mirror are deleted, not ported.
    school_year       academic_year NOT NULL,

    -- WHOSE year that is. 'stated_by_source': the state's page named it.
    -- 'reading_year': the page named none, so the row carries the school year we
    -- READ it in, and says so. Most of these pages describe a standing programme
    -- with no year on it at all, and the alternative to this column is inventing
    -- a year on the page's behalf -- which is exactly what this brief exists to
    -- refuse. The coach speaks the difference: "the page names the 2026-27 year"
    -- is a different sentence from "we read this in the 2026-27 year".
    school_year_basis TEXT          NOT NULL,

    participation     TEXT          NOT NULL,

    -- Whether the state requires every public high school to HOLD the sitting,
    -- which is a different fact from whether a student must sit it. Virginia is
    -- why the column exists: its law makes every school board run the SAT School
    -- Day and then says "no such student shall be required to participate", so
    -- `participation` is honestly 'offered' and that alone throws away the
    -- strongest sentence in the corpus -- in Virginia the day WILL be held at
    -- your school, which is nearly the school-level answer this table otherwise
    -- cannot give.
    --
    -- NULL is UNKNOWN and never false: we did not read this for most states, and
    -- "the state does not require schools to hold it" is a claim needing its own
    -- page, exactly like a 'none' programme row.
    school_must_offer BOOLEAN       NULL,

    test_chooser      TEXT          NOT NULL,
    test              TEXT          NULL,
    lowest_grade      SMALLINT      NULL,
    highest_grade     SMALLINT      NULL,
    -- Who pays, which is the headline claim of this slice -- so it is a column
    -- read from a page and never an inference from participation. NULL means the
    -- state's own page does not say, which is the commonest case (22 of 31), and
    -- the coach then says we do not know who pays rather than that the sitting is
    -- free. Nothing here records a price: "the state pays" and "the page does not
    -- say" are the only two things these sources support.
    state_funded      BOOLEAN       NULL,
    source_url        TEXT          NOT NULL,
    archive_url       TEXT          NULL,
    -- TWO CLOCKS, ON PURPOSE (Ian, 2026-09-14). A DATE is a day in the WORLD:
    -- the day a human read the state's page, and the day this reading stops
    -- being believed. Neither has an hour, and giving one a timezone would
    -- invent precision the fact does not have. A TIMESTAMPTZ is a fact about
    -- OUR ROW: when we wrote it and when we last touched it, which are instants
    -- and belong on a clock. The mixture is the rule, not an oversight.
    checked_on        DATE          NOT NULL,
    expires_on        DATE          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- The natural key of an append-only corpus, and what the generated seed
    -- upserts on. One state, one school year, one answer; a re-read of the same
    -- state in a LATER year is a new row, and a corrected re-read of the SAME
    -- year is a correction of that row rather than a second contradictory one.
    UNIQUE (state, school_year),

    CONSTRAINT admission_test_state_programmes_state_format_check
        CHECK (state ~ '^[A-Z]{2}$'),

    CONSTRAINT admission_test_state_programmes_school_year_basis_check
        CHECK (school_year_basis IN ('stated_by_source', 'reading_year')),

    -- A row that says the year is OURS must carry the year we actually read it
    -- in: the academic year of checked_on, under the same 1 July boundary the
    -- rest of this product's school years use. Without it, 'reading_year' would
    -- be an unfalsifiable label on any number in range.
    CONSTRAINT admission_test_state_programmes_reading_year_check
        CHECK (school_year_basis <> 'reading_year'
               OR school_year = EXTRACT(YEAR FROM checked_on)::INT
                                - CASE WHEN EXTRACT(MONTH FROM checked_on) < 7 THEN 1 ELSE 0 END),

    -- A page may legitimately name NEXT school year; it cannot name 2056. The
    -- bound matters more here than it looks: the read and the staleness guard
    -- both take the NEWEST row per state, so a typo'd future year would become
    -- that state's permanent answer AND would never be stale.
    CONSTRAINT admission_test_state_programmes_school_year_horizon_check
        CHECK (school_year <= EXTRACT(YEAR FROM checked_on)::INT
                              - CASE WHEN EXTRACT(MONTH FROM checked_on) < 7 THEN 1 ELSE 0 END
                              + 1),

    -- OUR OWN enumerations (CLAUDE.md), so TEXT + CHECK IN (...) plus exactly
    -- one Kotlin enum class each with a fromValue companion.
    --
    -- TWO AXES AND NOT ONE, and the data is what settled it. The first draft
    -- fused them into a single 'required | offered | district_choice' column,
    -- which cannot express either of the two states that broke it: Tennessee
    -- REQUIRES every eleventh grader to sit one and lets the DISTRICT pick
    -- which, and South Carolina OFFERS both and lets the STUDENT pick. Who must
    -- sit it and who chooses it are independent facts about a state, and a
    -- family hears them as different sentences.
    CONSTRAINT admission_test_state_programmes_participation_check
        CHECK (participation IN ('required', 'offered')),

    CONSTRAINT admission_test_state_programmes_test_chooser_check
        CHECK (test_chooser IN ('state', 'district', 'student')),

    CONSTRAINT admission_test_state_programmes_test_check
        CHECK (test IS NULL OR test IN ('sat', 'act')),

    -- The pairing, and it is the honesty of the row (the RFC 197
    -- score-release-citation precedent). The state names a test exactly when the
    -- state is the one that chose it. Either half alone is a claim no source
    -- supports: a district-chosen row carrying 'sat' says the state chose after
    -- all, and a state-chosen row carrying no test tells a family to sit
    -- something unnamed. Arkansas is why the third chooser exists: its 2025 law
    -- funds a MENU (ACT, SAT, CLT), so the state chose none of them, and the
    -- first draft would have recorded it as an ACT state it is not.
    CONSTRAINT admission_test_state_programmes_test_pairing_check
        CHECK ((test IS NOT NULL) = (test_chooser = 'state')),

    -- The grade or grades the sitting happens in -- almost always 11 alone, and
    -- Minnesota's statute covers grade 11 OR 12, which a single column could
    -- only have rendered as a guess at one of them. BOTH ends travel together or
    -- neither does: NULL is legal and means the source named no grade, and is
    -- never completed with a guess, because "your junior year" is the sentence a
    -- wrong guess here breaks.
    CONSTRAINT admission_test_state_programmes_grade_pairing_check
        CHECK ((lowest_grade IS NULL) = (highest_grade IS NULL)),

    CONSTRAINT admission_test_state_programmes_grade_range_check
        CHECK (lowest_grade IS NULL
               OR (lowest_grade BETWEEN 9 AND 12
                   AND highest_grade BETWEEN 9 AND 12
                   AND lowest_grade <= highest_grade)),

    -- The state's own page, read on checked_on, and an archive capture OF THAT
    -- SAME PAGE where one was needed -- the citation shape RFC 140 already uses
    -- for the Common Data Set (source_url + archive_url), reused rather than
    -- reinvented. Arizona's and New Hampshire's departments return 403 to every
    -- client we have, and those rows were read from captures of those official
    -- URLs; recording only the live URL would hide how the row was read, and
    -- recording only the capture would hide whose page it is.
    --
    -- http IS ACCEPTED (Ian, 2026-09-14: "https only -- why? We should prefer
    -- https, but not block insecure"). A state agency page served over plain
    -- http is still the state's own page, and refusing it would drop a true row
    -- over a transport detail. The rule the citation actually needs is that
    -- there IS a page: a non-empty URL that looks like one. Prefer https when
    -- the same page is reachable both ways; never reject a row for it.
    CONSTRAINT admission_test_state_programmes_source_url_check
        CHECK (source_url ~ '^https?://'),

    CONSTRAINT admission_test_state_programmes_archive_url_check
        CHECK (archive_url IS NULL OR archive_url ~ '^https?://'),

    -- The day this reading stops being believed. It is a fact about the READING
    -- and not a promise about the world, which is what keeps it honest in an
    -- append-only corpus: past it, the coach says we hold no current record for
    -- that state and points at the state's page, and the row still says what we
    -- believed and when.
    CONSTRAINT admission_test_state_programmes_expiry_after_checked_check
        CHECK (expires_on > checked_on)
);

-- NO INDEX, deliberately, on the RFC 197 reasoning. The only read in the tree is
-- AdmissionTestStateProgrammeDao.listNewestPerState -- one DISTINCT ON over a
-- table whose whole history is at most a few hundred rows -- which reads
-- everything whatever indexes exist. The staleness guard needs every state's
-- newest row, and the per-student lookup is a map over a list already in memory.
-- An index describing a query nobody wrote is worse than no index. When this
-- corpus has ten years of history and a real filtered read, the index arrives
-- with that read.

CREATE TRIGGER trigger_03_enforce_admission_test_state_programmes_updated_at
BEFORE UPDATE ON admission_test_state_programmes
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- A SECOND OFFICIAL DOCUMENT IS NOT MODELLED HERE, DELIBERATELY (Ian,
-- 2026-09-14). Two states in the first corpus -- Utah and Delaware -- have
-- claims split across two state publications, and this file briefly carried a
-- child table for them. It was dropped before landing: storing links to external
-- documents, and the archives of them, is a general need this repo meets in at
-- least eight places today with a pair of url columns, and it is getting a
-- general `external_documents` model with a mapping table in its own slice, plus
-- a cleanup that migrates every one of those columns -- these two included. A
-- per-corpus sources table would have been a ninth private answer to a question
-- about to get a public one.
--
-- So a row cites ONE document: the page the coach links, whose claims that page
-- supports. A state whose page does not support the whole row does not ship a
-- row (Utah, held out for exactly this reason); the second document is recorded
-- in the authored corpus's notes, where the human re-reading the row next year
-- is already looking.
```

### 2. Why absence, and not a `none` row

The research lead for this data is a secondary aggregate (PrepScholar, October
2025), and these programmes are re-funded yearly, so every row needs a state's
own page behind it. The corollary is that a state we could not source primarily
has **no row**, and absence therefore means "we did not read a source".

The alternative — a `participation = 'none'` row — asserts the negative, which
needs a primary page saying the state runs nothing, which state assessment pages
mostly do not say. A wrong `none` tells a family to pay for a Saturday they did
not need: the failure this slice exists to remove, pointing the other way. There
is no `none` member, no row carrying one, and no enum arm no row can reach.

**What was actually sourced: 31 of 51 jurisdictions**, from four parallel
research passes recorded under the run scratch. 25 require the sitting and 6
offer it; 15 are ACT states, 10 SAT, and 6 name no state-chosen test; 9 pages
say who pays and 22 do not. The 20 with no row are named in `PROVENANCE.json`,
and four of them — DE, TX, UT, VA — are recorded as believed-to-run-one with an
unreadable primary page, so the next re-check starts there rather than from
nothing.

### 3. Kotlin model: the illegal row does not typecheck

`:db`,
`db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestStateProgramme.kt`.

- `SchoolDayParticipation(val value: String)` — `REQUIRED`, `OFFERED`.
- `TestChooser(val value: String)` — `STATE`, `DISTRICT`, `STUDENT`.
- `SchoolYearBasis(val value: String)` — `STATED_BY_SOURCE`, `READING_YEAR`.

Each is one Kotlin enum with a `fromValue` companion, per CLAUDE.md's own-
enumeration rule.

The `(test_chooser, test)` pairing the schema enforces is enforced again by the
type, on the `ScoreRelease` precedent RFC 197 set — three independent nullables
would let the Kotlin side hold a state the database rejects:

```kotlin
sealed interface SchoolDayTestChoice {
  /** The state chose the test, and names it. */
  data class StateChose(val test: AdmissionTest) : SchoolDayTestChoice

  /** The state chose no test: the district or the student does. */
  data class ChosenLocally(val chooser: TestChooser) : SchoolDayTestChoice
}
```

`GradeRange(lowest: Int, highest: Int)` is the same move for the grade pair:
either both ends or neither, never a half-open range in memory.

`schoolMustOffer: Boolean?` rides beside `participation`, and its null is
UNKNOWN. Nothing in the read or the copy is allowed to turn that null into "the
state does not require it": the two are different claims and only one of them is
sourced.

The additional-document types this design briefly carried are gone with the
child table (see above): a row cites one document, in its own two columns.

`AdmissionTestStateProgramme` carries `state: String`,
`schoolYear:
AcademicYear` (the `:common` type — `:db` row types already name
it, per its own KDoc), `schoolYearBasis`, `participation`, `choice`,
`grades: GradeRange?`, `stateFunded: Boolean?`, `sourceUrl`,
`archiveUrl: String?`, `checkedOn`, `expiresOn`, plus the `New*` write model.

`HandTypedReferenceData.REFRESH_LEAD` (`:db` models) becomes the one home for
D20's ninety days. `AdmissionTestAdministration.CALENDAR_REFRESH_LEAD` moves
into it and its three references are updated: copying the number would put one
rule in two places under two names.

`AdmissionTestStateProgrammeDao.listNewestPerState(session)` is the only read:

```sql
SELECT DISTINCT ON (state) * FROM admission_test_state_programmes
ORDER BY state, school_year DESC, checked_on DESC
```

One statement, whole corpus. `checked_on DESC` breaks the tie a same-year
correction creates.

### 4. The authored corpus and its generator

    db/seed/test-state-programmes/programmes.csv
    db/seed/test-state-programmes/PROVENANCE.json
    bin/test-state-programme-seed  ->  db/schema/seed-admission-test-state-programmes-<reading-year>.sql

Header, fixed and refused if it differs, so no column is ever read by position:

    state,school_year,school_year_basis,participation,school_must_offer,test_chooser,test,lowest_grade,highest_grade,state_funded,source_url,archive_url,checked_on,expires_on

A second authored file for the additional documents was designed and dropped
with the child table; a row cites one document.

`bin/test-state-programme-seed` is `bin/test-calendar-seed`'s shape, and the
shape is not negotiable: `getopts` with short options only, every diagnostic on
stderr, nothing on stdout on a normal run, one `awk` pass that validates every
row and reports every fault with its line number before anything is written,
generation into a `mktemp -d` scratch, `-n` as a dry run exiting
`EXIT_SEED_WOULD_CHANGE` when the tree differs, `schema_order_lists` asked of
`bin/common` rather than a re-implemented `grep -Fxq`, and a
`MIRRORS <constraint>` comment on every check that mirrors a `CHECK` in the DDL,
with a scripts-test asserting the mirror set IS the DDL's constraint set.

Three differences from the calendar generator, each deliberate:

- **The seed is named for the reading session**, not for a cycle:
  `seed-admission-test-state-programmes-2026.sql`, the academic year of the
  session's `checked_on`. One session may legitimately produce rows for two
  school years — a page that names 2025-26 and a page that names none — so the
  cycle cannot name the file.
- **The school-year rules are arithmetic, not a regex.** The validator checks
  that `school_year` is an integer in the `academic_year` range, that a
  `reading_year` row carries the academic year of its own `checked_on`, and that
  no row names a year beyond the next one. The `'2026-27'` regex and its DDL
  twin are gone.
- **Append-only is checked, not assumed.** The generator refuses a CSV that
  drops or rewrites a `(state, school_year)` pair the schema directory's
  previously generated seeds already contain. The corpus grows; it does not get
  edited.

Retained from the sibling: the rows of one session must agree on `checked_on`
and `expires_on`, because those are facts about the session and a refresh that
missed a row would otherwise expire quietly.

### 5. The read

`:service`, `service/src/main/kotlin/ed/unicoach/coaching/testing/`.

`SchoolDayTestingReader` answers one typed value from the student's money
profile and the newest row per state:

```kotlin
sealed interface SchoolDayProgramme {
  data class Held(val row: AdmissionTestStateProgramme) : SchoolDayProgramme
  data class Expired(val state: String, val expiresOn: LocalDate) : SchoolDayProgramme
  data class NotHeld(val state: String) : SchoolDayProgramme
  data class StateUnknown(val reason: ResidencyGap) : SchoolDayProgramme
}

enum class ResidencyGap { NO_PROFILE, UNANSWERED, DECLINED }
```

`Expired` is per state, not per corpus: one lapsed state must not withhold the
forty current records we hold.

`TestTimelineService.readInSession` gains those two statements and returns the
value on `TestTimeline.Resolved`. The KDoc saying the read costs five statements
becomes seven and is **asserted** against a named constant — RFC 187 found a doc
comment stating a count that no test could contradict.

### 6. The surface, and the order it is said in

`admission_test_timeline`'s `Resolved` payload gains one object:

```json
"school_day_programme": {
  "state": "TN",
  "held": true,
  "participation": "required",
  "test_chooser": "district",
  "test": null,
  "test_name": null,
  "chooser_label": "the district chooses which test",
  "lowest_grade": "11",
  "highest_grade": "11",
  "state_funded": true,
  "school_must_offer": null,
  "school_year": "2026-27",
  "school_year_basis": "reading_year",
  "school_year_basis_label": "the state's page names no school year; this is the year we read it in",
  "source_url": "https://...",
  "archive_url": null,
  "checked_on": "2026-09-14",
  "expires_on": "2027-07-31",
  "what_the_state_cannot_answer": "whether this student's own school runs it, and on what date",
  "why": "<one sentence, built from the typed value, exhaustive with no else>"
}
```

Every key is always present, as an explicit `null` where there is nothing — RFC
197's rule, twice found by its own review: an absent key is a silence the model
fills. `school_year` is the `AcademicYear` LABEL, from the one home for it,
never a bare number. Grades are strings, like every other value in this tool,
whose `NUMBERS_BY_CONTRACT` is empty and stays empty.

The `NoCalendar` (refusal) arm is unchanged and carries **no** programme
section. RFC 197's refusal payload states that it carries no date at all, and
`checked_on` is a date. An expired national calendar is already a build failure
ninety days earlier, so what the omission costs is a state fact withheld in a
state the build is already shouting about. It is the one thing this RFC gives
up, and it is recorded rather than left as a silent gap.

`SchoolDayCopy` (`:service`, beside `AdmissionTestCopy`) is the one home for the
participation, chooser and basis words: storage enums carry no English.

### 7. Copy, reviewed for self-consistency and sourceability before it ships

RFC 187's two most valuable findings were prompt defects — a block that banned
an aggregate claim and then ordered one, and an explanation instructing claims
no tool can source. A prompt block is code the model executes, so every sentence
below names a field this tool emits, and none instructs a claim we do not hold.

Appended to `prompts/coach-system-prompt.md`, one sentence per line, after the
existing admission-test timeline block:

1. Some states run the SAT or the ACT inside the school day and pay for it, and
   the timeline tool's `school_day_programme` section is what that student's own
   state does — read it before you suggest registering for a Saturday sitting.
2. Say the national picture first and the state's in-school sitting second: the
   in-school sitting is an exception to the national calendar, not the way into
   the explanation.
3. State only what the section says, with the school year it covers and the day
   we read the state's page, and say that a state can change this from year to
   year and that the state's own page is where to confirm it.
4. Read `school_year_basis` before you say the year: where it is the state's own
   stated year say so, and where it is the year we read the page in say that
   instead — never present ours as theirs.
5. Where the section says the state pays, say the family does not pay for that
   sitting; where it says nothing about who pays, say we do not know who pays.
6. The section is about the **state** and never about their school: never say
   their school runs a test day, never name an in-school test date, and never
   read a state programme as a school's plan.
7. The ONE exception, and only when `school_must_offer` is true: the state
   requires every public high school to hold the sitting, so you may say the day
   will be held at their school — and where it is null say nothing either way,
   because null means we did not read it and never that the state does not
   require it.
8. You may ask once, plainly, whether their school runs it and when — nothing
   waits on the answer, "I don't know" is a complete answer, and you never ask
   again.
9. Where the section says we hold nothing for that state, say we do not hold a
   record for it — a gap in our data, not the state running nothing — and never
   invent a programme.
10. Where we do not know what state they live in, do not ask for it here: the
    residency question belongs to the cost conversation, and no answer of yours
    waits on it.
11. Where the section names a grade the student has not reached, say when it
    comes round; where they are past it, say so and work from the national
    sittings — both come from the grades in the section and the year of school
    you are already told, never from a guess.
12. Where the state left the choice of test to the district or to the student,
    the state has not chosen one: name both tests, say who chooses, and never
    present one of them as theirs.

The tool's model-facing `DESCRIPTION` gains the same rules in the compressed
form that file uses, because a prompt version pin can be rolled back while the
tool stays registered (RFC 197's partial-rollback finding).

### 8. Staleness, redesigned for an append-only corpus

The guard is on the **newest row per state**, and both halves of that phrase
carry weight.

- **Newest**, because the corpus is append-only: last year's row keeps its own
  `expires_on` forever, so a minimum over ALL rows is the unclearable bar RFC
  197's review caught. Here the clearing action — append this year's row for
  that state — moves the number the guard reads.
- **Per state**, because a corpus-wide "something is fresh" bar would let a
  state nobody re-checks hide behind a fresher neighbour. That was this design's
  own objection to a liveness filter, and per-state is what answers it.

A state with no row at all is **not** a failure: absence is an honest state and
the coach says so. What is a failure is the corpus shrinking, so a floor over
the DISTINCT states present guards a truncated or half-written file, on the
calendar's `MINIMUM_AUTHORED_ROWS` precedent and with its reason written down.

### 9. The key that could not be landed, and the slice that carries it

Ian asked for `state` to be a foreign key into `us_states`, and he was right to.
It is not in the landed table, and this is the whole reason.

`us_states` is CREATED by a migration (0060) and POPULATED by the data-import
run. This corpus is seeded BY MIGRATION — deliberately, because `bin/test` never
imports and the RFC 197 lesson is that an import-loaded corpus is absent from
every test database. So the seed's rows arrive while the codebook table is still
empty, and the verify run refused them exactly there:

    ERROR: insert or update on table "admission_test_state_programmes" violates
    foreign key constraint "admission_test_state_programmes_state_fkey"
    DETAIL: Key (state)=(AL) is not present in table "us_states".

**This is not a test-database artefact.** A fresh production database migrates
before it imports and would refuse the same seed. The two landed tables that do
key into `us_states` (0067: `colleges`, `college_search_index`) never meet it,
because their rows also arrive at import time, after the codebook.

Note what did NOT fail. The generator's own harness passed in full at that
moment, including "the committed seed is exactly what the committed CSV
generates". The generator and its output agree with each other; what failed was
applying that seed to a database with no codebook in it. Those are different
statements and a reader should not be able to confuse them.

Ian named the defect rather than the workaround: _"Unicoach owns the us_states
table, so it should populate it."_ **`test/05.3/own-our-codebooks` carries the
fix** — it seeds the published codebooks at MIGRATION time from their authored
files, the same shape this corpus already uses, and then adds the keys that
could not exist before, this one included. The codebook has one writer either
way; what changes is WHEN it is written, which is the actual bug.

Until then: the column carries the same `^[A-Z]{2}$` rule
`money_profiles.residency_state` carries, and MEMBERSHIP is asserted over the
committed file — `AdmissionTestStateProgrammeSeedTest` reads every state code
against `db/data/codebooks.json`, so a typo fails the build in the file, where
the fix is, rather than at apply time in somebody else's `db-reset`. The DAO
test says the same thing out loud, by asserting that a badly shaped code is
refused and that a well-shaped unpublished one is NOT: a test claiming a
database guarantee we do not have would be worse than the gap.

**Third time in this brief.** Hand-maintained reference data needed to exist at
migration time for the calendar (RFC 197) and again here; both had to invent the
pattern. The codebooks are the table that already had it backwards.

## Files Modified

**Added**

- `db/schema/create-admission-test-state-programmes.sql` — the DDL above (RFC
  180 slug, no number; the `db/schema/ORDER` line is placed by `ship-order` at
  land).
- `db/schema/seed-admission-test-state-programmes-2026.sql` — GENERATED.
- `db/seed/test-state-programmes/programmes.csv`, `.../PROVENANCE.json`.
- `bin/test-state-programme-seed`,
  `bin/test-state-programme-seed-scripts-tests`.
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestStateProgramme.kt`,
  `.../models/HandTypedReferenceData.kt`.
- `db/src/main/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeDao.kt`.
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeFixture.kt`.
- `db/src/test/kotlin/ed/unicoach/db/dao/AdmissionTestStateProgrammeSeedTest.kt`,
  `.../AdmissionTestStateProgrammeDaoTest.kt`.
- `service/src/main/kotlin/ed/unicoach/coaching/testing/SchoolDayTestingReader.kt`,
  `.../SchoolDayCopy.kt`.
- `service/src/test/kotlin/ed/unicoach/coaching/testing/SchoolDayTestingReaderTest.kt`.

**Changed**

- `service/.../testing/TestTimelineService.kt` — two statements, one field on
  `Resolved`, the statement-count KDoc.
- `service/.../testing/AdmissionTestTimelineChatTool.kt` — the section, its
  `why` sentences, the `DESCRIPTION` rules.
- `db/src/main/kotlin/ed/unicoach/db/models/AdmissionTestAdministration.kt` and
  its two readers — `CALENDAR_REFRESH_LEAD` moved to `HandTypedReferenceData`.
- `prompts/coach-system-prompt.md` — the twelve sentences. The seed migration
  and the `systemPromptVersion` pin are `bin/prompt-seed`'s at land, never
  hand-written.
- `bin/shell-tests` — the new scripts-test in the aggregator.
- `db/schema/ORDER` — two lines, placed by `ship-order`.
- `service/src/test/.../{TestTimelineServiceTest,AdmissionTestTimelineChatToolTest}.kt`.

**Not touched:** `.claude/**`, `product/**`, `money_profiles` and every other
money table, `admission_test_administrations`' own DDL, `admin-web`, iOS, and
the wording of any existing answer.

## Implementation Plan

1. The corpus is already researched: four parallel passes, one file per block
   under the run scratch, plus a consolidated draft. Six rows whose
   participation/chooser split was decided by the coordinator rather than by the
   researcher are re-verified against the source files before they are seeded,
   and the two rows read from an archive capture get their `archive_url`.
2. `programmes.csv` + `PROVENANCE.json`, with coverage — read, and not read —
   recorded in the manifest.
3. DDL + `bin/test-state-programme-seed` + its scripts-test; generate the seed;
   `nix develop -c bin/db-reset` to prove it applies.
4. `:db` model, DAO, fixture; the seed test and the DAO test.
5. `SchoolDayTestingReader`, the `TestTimelineService` field, the tool section
   and its copy; the constant move.
6. Prompt sentences, authored one per line.
7. `nix develop -c bin/format -c`, then the full suite, then tiered review.

## Tests

Executed counts come from the JUnit XML `tests=` attribute, never from
`grep -c "@Test"`.

**`AdmissionTestStateProgrammeSeedTest`** (`:db`, reads the committed file, no
database). It lives here and not beside the tool for the reason the spec gives:
a guard inside the artifact under rewrite is not a guard.

1. No state's **newest** row is within `REFRESH_LEAD` of its `expires_on`, or
   past it — the D20 build failure, with a message naming the state, the CSV,
   the script and what to re-read.
2. **The guard is clearable by the action it asks for.** On a clock past the
   committed expiry it fails; APPENDING next year's row for every state clears
   it, and the landed rows are still present, unedited.
3. **A partial refresh does not clear it**: append for all but one state and
   that state still fails, named. This is the direction the calendar's original
   defect hid in.
4. **A stale row cannot hide behind a fresh neighbour**: one state left behind
   fails even though the corpus as a whole is fresh.
5. `PROVENANCE.json` against the file: both key sets whole, `sha256`, the row
   count, and the manifest's `checked_on` / `expires_on` against the rows.
6. The fixture's header is the generator's own `EXPECTED_HEADER`.
7. Every `(state, school_year)` pair is unique, every `state` is a real
   `us_states` code, and the DISTINCT state count is at or above the floor.
8. Every row's typed shape is legal: a locally-chosen row names no test, a
   state-chosen row names one, a `reading_year` row's year is its `checked_on`'s
   academic year, and no row names a year beyond the next one.

**`bin/test-state-programme-seed-scripts-tests`** (in `bin/shell-tests`): usage
errors and their reserved exit codes, stdout empty on a normal run, a refused
bad header, one fault per bad row with its line number, every fault in ONE pass,
`-n` exiting `EXIT_SEED_WOULD_CHANGE` on a stale tree and 0 on a current one,
nothing written on refusal, a refused attempt to drop or rewrite an
already-generated `(state, school_year)` pair, and the mirror-set assertion.

`school_must_offer` gets its own case in the reader tests: true is spoken as
"the day will be held at your school", and null says nothing either way.

**`AdmissionTestStateProgrammeDaoTest`** (database): a row round-trips; a second
school year for the same state is a second row and `listNewestPerState` returns
the newer; a same-year correction upserts in place; the foreign key refuses a
state code `us_states` does not publish; and each CHECK refuses its own bad row
(bad basis, a `reading_year` year that is not the reading year, a year beyond
next, locally-chosen with a test, state-chosen with none, a half grade range, an
inverted grade range, a URL that is neither http nor https, expiry on or before
`checked_on`).

**`SchoolDayTestingReaderTest`**: `Held`, `Expired`, `NotHeld`, and
`StateUnknown` for each of `NO_PROFILE`, `UNANSWERED` and `DECLINED`; and a
state with two years of rows, proving the newer is the one spoken. The clock is
an `InstantSource` and today is
`LocalDate.ofInstant(clock.instant(), COACH_ZONE)`; `LocalDate.now(clock)`
appears nowhere (RFC 186's finding — a test using it passed for the wrong
reason).

**`AdmissionTestTimelineChatToolTest`**, the door, and D22's spread:

1. A junior in a state that requires it gets the programme, its school year with
   whose year it is, its source and its `checked_on`, and **is asked nothing** —
   asserted on the payload, not on prose.
2. A **senior**, past the programme grade, gets the section with the grade fact
   and no suggestion the sitting is ahead of them.
3. A student with **no money profile at all** gets `state_unknown` with
   `NO_PROFILE`, no state, and no invented programme.
4. A student with an **empty college list** still gets the section: there is no
   anchor, and the state fact does not depend on one.
5. A student **past graduation** gets the section with no in-school claim that
   contradicts the year of school.
6. A **district-chooses** state names no test and says the district decides; a
   **student-chooses** state says the student does.
7. A state we hold **no row** for says we hold no record, never that the state
   runs nothing.
8. **No rendered school-day section names a school or says "your school"** —
   asserted as a property over every case above, so a later sentence cannot
   reintroduce it.
9. The refusal arm still carries no date and no programme section.

**Prompt.** The existing copy tests cover assembly; the twelve sentences are
reviewed for self-consistency and sourceability at tier review, which is where
RFC 187's two defects were caught.

## What this RFC does not do

- No reminder, no notification (D11).
- No school-level participation data, and no table for it. The question stays a
  question.
- No `none` row and no negative assertion about any state (section 2).
- No date formatter and no new date wording (D21); `test/04.5` still owns that.
- No stored score, no prep, no prediction (D9, `test/06`).
- **No capture DATE as a field, and New Hampshire is the worked example.** An
  archive link is not a spare link: it is the state's page as it was on the day
  it was captured (brief 0010 D26). Three rows were READ FROM their capture,
  because those state pages refuse every client we have — and New Hampshire's
  capture is from **2 February 2023**. So that row states what the page said in
  February 2023, however recently we looked at it, and nobody can re-fetch the
  live page from here. `checked_on` stays the day WE read it, because that is
  our re-reading cadence and is what the staleness guard measures; the capture
  dates are recorded per state in `PROVENANCE.json`, and the coach says "an
  archived copy of the state's page" without a date rather than implying the
  live page says this today. Giving a capture a first-class date belongs to
  `test/05.1/external-documents`, and **New Hampshire is the case that RFC
  should name**: the alternative, refreshing `checked_on` yearly while the
  content silently stays at 2023, is exactly the rot this brief exists to make
  loud.
- **No record that a programme ENDED, and North Dakota is the cost of that.**
  Its own page says the state stopped paying for the ACT from 2021-22 and
  discontinued the district option after 2023-24. We hold no row, which the
  coach reads out as "we hold no record for that state" — true, and weaker than
  what we actually know. Inventing a `none` row for it would break section 2's
  rule for one state. It is named here as the known limit of what this table can
  say, and it is the case a later ended-programme record would be built for.
- **No practice tests, and Oregon is why that is a decision.** Oregon runs a
  state-wide, state-funded, in-school exam in grade 10 under ORS 329.488, and it
  is the PreACT. Writing it down as the ACT would tell a family a college will
  see that score. It gets no row.
- No change to `money_profiles`, and no correction of
  `admission_test_administrations`' own two inconsistencies — both are named
  above as follow-ups with their own slices.
