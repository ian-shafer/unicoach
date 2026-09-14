# RFC 195 — the range, not the average: 25th/75th percentile score bands

**Slice:** `test/03/the-range-not-the-average` (brief 0010,
`product/0010-when-do-i-test/spec.md`). **Lane:** A (rfc) — new DDL, a new
coach-facing section, and a product rule (D16) about what we say when the band
is missing. **Base:** `main@d2fc8bdf`. **Branch:** `pipeline/rfc-195`.

## The question this answers

A student asks about a school on her list and is told that school's admitted
students _averaged_ 1310. That number reads as a bar. What she can actually
locate herself in is the **range**: the middle half of admitted students scored
between X and Y. This slice stores the published 25th/75th percentile bands and
puts them in front of the coach beside the test policy RFC 187 landed.

D9 is inherited and absolute: no test prep, no score prediction, no chancing.
The band is a fact about last year's admitted class, never a target.

## What was measured, not asserted

Every number below was measured on this machine at run start, against the
**fetched** (gitignored, `.gitignore:54`) snapshot in `db/seed/scorecard/`
(`PROVENANCE.json` release `06102026`, 6,273 institution rows).

| measure                                                     | count     |
| ----------------------------------------------------------- | --------- |
| institution rows                                            | 6,273     |
| `SATVR25`/`SATVR75` present                                 | 949 each  |
| `SATMT25`/`SATMT75` present                                 | 949 each  |
| `ACTCM25`/`ACTCM75` present                                 | 920 each  |
| `SAT_AVG` present (`colleges.sat_average_equivalent_score`) | 1,037     |
| **any** band (SAT or ACT)                                   | **1,039** |
| `SAT_AVG` present and **no SAT band**                       | **88**    |
| `SAT_AVG` present and **no band of any kind**               | **0**     |
| band present and no `SAT_AVG`                               | 2         |

Three facts from that table bind the design.

1. **The pairs are exact.** `25` and `75` are present or absent together in all
   three families (0 mismatches), `SATVR` and `SATMT` are present on exactly the
   same rows, every cell is a plain integer, no `PrivacySuppressed` or `NA`
   token appears, and no row has `25 > 75`. The schema can therefore state the
   pairing as a CHECK rather than hope for it.
2. **The spec's premise for D16 is half right.** 88 colleges have an SAT average
   and no SAT band — but **all 88 of them have an ACT band**, so the count of
   colleges with an average and nothing else is **zero**. Under the letter of
   D16 ("never show both for the same school") the fallback would never fire.
   See decision **D-A**.
3. **Two colleges publish an ACT band and no average**, and both are junk:
   Southeastern Baptist College `ACTCM25=5, ACTCM75=7`, Conception Seminary
   College `ACTCM25=1, ACTCM75=5`. The ACT scale starts at 1, so these are in
   domain and a CHECK cannot exclude them. See decision **D-D**.

The year was measured too, from the publisher's own workbook
(`CollegeScorecardDataDictionary.xlsx`, 727,278 bytes — the same artifact
`db/seed/scorecard/FETCH-NOTES.md:117` records). Sheet
`Most_Recent_Inst_Cohort_Map` dates all six band variables, and `SAT_AVG` with
them, to:

> **Fall 2024, reported in IPEDS DCY2024-25**

and sheet `Institution_Data_Dictionary` gives `SOURCE = IPEDS` for all six, with
`SATVR*` labelled _critical reading_ and `SATMT*` _math_. So the band's honest
date is an **entering cohort term** (autumn 2024), not an academic year — which
is why it does not go in the money seam's academic-year vintage machinery (D-B).

## Detailed Design

### The DDL — presented in full (brief 0001 D10)

One migration, `db/schema/add-college-score-bands.sql`. RFC 180: a kebab slug,
no number; its apply order is one line appended to `db/schema/ORDER` by
`ship-order` at land. Three statements in one transaction, and the ORDER is the
whole point — `0094:57-62` records the trap: a plpgsql body is stored as TEXT
and is **not** dependency-tracked, so a stale `log_college_version()` passes the
migration and then kills the very next write to `colleges` with
`42703 record "new" has no field ...`, from inside the trigger, mid-ingest.

```sql
-- Part 1 -- six columns on the live table, with their domains.
ALTER TABLE colleges
    ADD COLUMN sat_reading_25th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_reading_75th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_math_25th_percentile_score      INTEGER NULL,
    ADD COLUMN sat_math_75th_percentile_score      INTEGER NULL,
    ADD COLUMN act_composite_25th_percentile_score INTEGER NULL,
    ADD COLUMN act_composite_75th_percentile_score INTEGER NULL,
    -- The published scales, not a guess: an SAT section is 200-800, an ACT
    -- composite is 1-36. A value outside them is a loader fault, never data.
    ADD CONSTRAINT colleges_sat_reading_25th_percentile_score_domain_check
        CHECK (sat_reading_25th_percentile_score IS NULL
               OR sat_reading_25th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_reading_75th_percentile_score_domain_check
        CHECK (sat_reading_75th_percentile_score IS NULL
               OR sat_reading_75th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_math_25th_percentile_score_domain_check
        CHECK (sat_math_25th_percentile_score IS NULL
               OR sat_math_25th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_sat_math_75th_percentile_score_domain_check
        CHECK (sat_math_75th_percentile_score IS NULL
               OR sat_math_75th_percentile_score BETWEEN 200 AND 800),
    ADD CONSTRAINT colleges_act_composite_25th_percentile_score_domain_check
        CHECK (act_composite_25th_percentile_score IS NULL
               OR act_composite_25th_percentile_score BETWEEN 1 AND 36),
    ADD CONSTRAINT colleges_act_composite_75th_percentile_score_domain_check
        CHECK (act_composite_75th_percentile_score IS NULL
               OR act_composite_75th_percentile_score BETWEEN 1 AND 36),
    -- A band is a PAIR. Half a band is not a range, and the snapshot has none:
    -- 0 mismatches over 6,273 rows in all three families.
    ADD CONSTRAINT colleges_sat_reading_band_paired_check
        CHECK ((sat_reading_25th_percentile_score IS NULL)
               = (sat_reading_75th_percentile_score IS NULL)),
    ADD CONSTRAINT colleges_sat_math_band_paired_check
        CHECK ((sat_math_25th_percentile_score IS NULL)
               = (sat_math_75th_percentile_score IS NULL)),
    ADD CONSTRAINT colleges_act_composite_band_paired_check
        CHECK ((act_composite_25th_percentile_score IS NULL)
               = (act_composite_75th_percentile_score IS NULL)),
    -- ... and an ORDERED pair. `>=`, not `>`: a school where the 25th and 75th
    -- percentile land on the same score is a real narrow class, not a fault.
    ADD CONSTRAINT colleges_sat_reading_band_ordered_check
        CHECK (sat_reading_75th_percentile_score IS NULL
               OR sat_reading_75th_percentile_score >= sat_reading_25th_percentile_score),
    ADD CONSTRAINT colleges_sat_math_band_ordered_check
        CHECK (sat_math_75th_percentile_score IS NULL
               OR sat_math_75th_percentile_score >= sat_math_25th_percentile_score),
    ADD CONSTRAINT colleges_act_composite_band_ordered_check
        CHECK (act_composite_75th_percentile_score IS NULL
               OR act_composite_75th_percentile_score >= act_composite_25th_percentile_score);

-- Part 2 -- the history mirror. NO constraints here, the 0045/0062 pattern:
-- the history table records what was written, and a constraint on it would
-- reject history the live table accepted.
ALTER TABLE colleges_versions
    ADD COLUMN sat_reading_25th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_reading_75th_percentile_score   INTEGER NULL,
    ADD COLUMN sat_math_25th_percentile_score      INTEGER NULL,
    ADD COLUMN sat_math_75th_percentile_score      INTEGER NULL,
    ADD COLUMN act_composite_25th_percentile_score INTEGER NULL,
    ADD COLUMN act_composite_75th_percentile_score INTEGER NULL;

-- Part 3 -- restate the history writer. The 0094 body verbatim plus the six
-- names on BOTH sides of the VALUES: 19 columns become 25. CREATE OR REPLACE,
-- never DROP/CREATE -- `trigger_04_log_college_version` binds by name and must
-- not be re-wired (the 0023/0045/0051/0057/0059/0062/0094 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, ipeds_unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment_headcount,
        admission_rate_share, sat_average_equivalent_score,
        sat_reading_25th_percentile_score, sat_reading_75th_percentile_score,
        sat_math_25th_percentile_score, sat_math_75th_percentile_score,
        act_composite_25th_percentile_score, act_composite_75th_percentile_score,
        completion_rate_150pct_4yr_share, website,
        aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.ipeds_unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment_headcount, NEW.admission_rate_share,
        NEW.sat_average_equivalent_score,
        NEW.sat_reading_25th_percentile_score, NEW.sat_reading_75th_percentile_score,
        NEW.sat_math_25th_percentile_score, NEW.sat_math_75th_percentile_score,
        NEW.act_composite_25th_percentile_score, NEW.act_composite_75th_percentile_score,
        NEW.completion_rate_150pct_4yr_share,
        NEW.website, NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

Plus `COMMENT ON COLUMN` for all six (the 0059 convention: the federal variable
name and the definition live in the comment, never in the column name), each
naming its Scorecard variable and the Fall 2024 cohort.

No backfill and no index. The next ingest fills them; existing history rows stay
NULL, which is correct — those ingests never saw the fields. Nothing filters on
a band, and `college_search_index` deliberately does not carry the SAT average
either (`0064:185`), so search is untouched by this slice.

### Ingest

`CollegeScorecardLoader` gains six `COL_` constants, six entries in
`REQUIRED_INSTITUTION_COLUMNS` (so a snapshot that drops a column fails loudly
before any write), and six `intInDomainOrNull` reads with the domains above —
the existing coercion machinery then logs and nulls an out-of-domain cell rather
than failing the run.

Those six reads are made **a pair at a time**, through
`CsvIngestSupport.intPairInDomainOrNull` returning a `CellPair`, and this is
load-bearing rather than tidiness. Part 1 above CHECKs that a band is a PAIR and
an ORDERED one, so six independent reads have a failure mode the flat shape
hides: one cell is coerced or inverted, the row is then REJECTED at the insert,
`upsertWithSavepoint` records a skip, and the ingest loses **the whole college**
— its name, its location, its enrolment — over one unusable score.

The helper lives in `CsvIngestSupport`, not in this loader, because it names no
test, no college and no Scorecard column: it reads "an optional pair of bounded
int cells, or neither", and its three siblings (`intInDomainOrNull`,
`doubleInDomainOrNull`, `logCoercion`) already live there. It takes an
`IntRange`, not a transposable `min`/`max`, and it drops a pair that is half,
inverted, or unreadable in either cell, carrying WHICH as a typed
`PairDropReason` rather than as prose in a log line.

The drop is counted in **its own tally**, `LoadCount.pairsDropped`, keyed by the
band with no suffix. An earlier draft of this RFC put those counts into
`fieldsCoercedToNull` under stem keys like `sat_reading_band_dropped`, and
defended the suffix here as "designed rather than discovered". It was neither: a
dropped BAND is a different event from a coerced CELL, that map's keys are
otherwise all `colleges` column names, and the test I wrote for it resolved the
state with `keys.filter { it.endsWith("_band_dropped") }` — parsing a key to
learn what it counted. Three review lenses named it independently. The two maps
are now separate and neither is parsed.

The pinned snapshot contains no half, inverted or unreadable pair (all three
families are exactly paired over 6,273 rows), so every one of these cases is
covered by a hand-built fixture, not by the corpus.

The six join `CollegesDao.NON_NULL_COUNTABLE_COLUMNS` and its twin
`NON_NULL_SUMMARY_COLUMNS`, so the ingest change summary counts them, and
`METHOD_VERSION` goes 9 -> 10: the summary's key set changes shape, and 9 is
what tells the old shape from the new (`0094:50-53`).

`CollegesDao` carries the six through the row mapper, the insert column list,
the `ON CONFLICT ... EXCLUDED` list and the "the full row rides" read. `College`
and `NewCollege` gain six nullable `Int` fields beside
`satAverageEquivalentScore` — flat, like every other Scorecard measure on that
row; the band becomes a _type_ one level up, at the service boundary, where the
rule that a band is a pair can be expressed once.

### The read: a fifth section of `college_admissions_profile`

`AdmissionsField` gains `SCORE_BANDS("score_bands")`, declared **after**
`TEST_POLICY` and before `DEADLINES`. Declaration order IS wire order (RFC 145),
and a score band belongs beside the policy: "does this school want a score" and
"what did the scores look like" are one thought, and the second is meaningless
before the first.

`SchoolScoreBands` (new file, beside `SchoolTestPolicy.kt`) is the section type,
built by a `from(college)` factory that returns **null** when the school has no
band and no average — the caller then names `SCORE_BANDS` in
`data_availability`, exactly as it does for the other four sections. It holds:

- `bands: List<ScoreBand>` — every band this school publishes, in `ScoredTest`
  declaration order. ONE list, not a bespoke `SatBands` holder beside a bare
  `act: ScoreBand?`: the wire renders a flat `bands[]` array, so that second
  shape existed nowhere but in the type, and a fourth band would have cost a
  field plus four edits. `ScoredTest` carries a `family` (SAT or ACT), which is
  what D-A is stated on.

  Two earlier drafts of this bullet were wrong and are recorded because the
  second mistake was invisible for the same reason as the first. The first said
  the reading and math sections were "present together or not at all, because
  the DB CHECK makes the half-present case unrepresentable". That is false about
  this RFC's own DDL: `colleges_sat_reading_band_paired_check` pairs the 25th
  with the 75th **within one section**, and nothing pairs reading with math. The
  measured snapshot happens to carry both on exactly the same 949 rows, which is
  why nothing contradicted the sentence;
- `admittedAverage: AdmittedAverage?` — the D16 fallback, present only under the
  rule in **D-A**, and a value class rather than an `Int` because it is the one
  figure here on the 400-1600 COMPOSITE scale while every band is on a section
  scale. As a bare `Int`, `statementOf(band.lower)` — a 200-800 section score
  spoken as a composite average — type-checked. It no longer compiles;
- `source: ScoreBandCitation` — one constant cohort, D-B.

`ScoreBand(test, lower, upper)` owns its own spoken form, on the
`CdsCitation.citedAs` and `TestPolicyCopy.statementOf` precedent: the sentence
the coach reads is COMPUTED from the numbers, never typed beside them, so the
two cannot drift. Its constructor is private and `ScoreBand.of` returns a sealed
`ScoreBandResult` — `Band` / `Absent` / `Malformed(test, lower, upper, reason)`
— rather than a null. A null folded FOUR states into one: a school that
publishes nothing (5,234 of 6,273), and three kinds of stored row that violate
this migration's own CHECKs — a half pair, an inverted pair, and a pair outside
its published scale. The first is ordinary and silent; the other three are
corpus corruption, and `CollegeAdmissionsService.scoreBandsOf` logs each one the
way `testPolicyOf` already logs a refused policy code.

**A band we hold and cannot trust is not "only an average exists".** This is the
sharpest rule in the slice and it is Ian's, as D16's author, confirmed at
review: `fallbackAverage` suppresses the average when ANY SAT-family result is
`Band` **or** `Malformed`. Otherwise a stored-but-unusable SAT pair would
promote the school into the D16 path and a family would be shown an AVERAGE
where the row actually holds a RANGE — invisible on the wire and in the log.
Such a school emits no section and is named in `data_availability`. Where we
hold a range we cannot trust, nothing is the right answer.

Note the distinction this slice keeps, because the two are easy to blur: we
REFUSE a figure that is impossible on its own published scale — that is the
`AdmittedAverage` gate, and the illegal case fails to COMPILE rather than
failing a test — and we PUBLISH a figure that is merely implausible, which is
D-D and the two junk ACT bands. Different claims. The `test` discriminant is
what makes that computation possible — the sentence has to name which test the
two numbers are about — and it is a member of `ScoredTest`, a small enum in the
same file carrying each test's wire name and its spoken label. `ScoredTest` is
also what lets the three bands ride ONE array with a per-band discriminator
(`test`, `test_label`) rather than three differently-named objects.

The citation object is `ScoreBandCitation`, **not** `ScorecardScoreBandCitation`
as an earlier draft named it. RFC 177 bans naming a canonical-money publisher on
a live line, and the guard reads the line, not the intent: spelling "Scorecard"
into the type name put the token on six live lines (the field, the factory, two
copy constants, the renderer and the declaration) and would have cost six
`money-attribution-exempt` waivers to spell one identifier. The publisher is
named ONCE instead, inside the cited sentence, with the marker and its reason
there. `SchoolScoreBands.kt` also carries a `putCitation(ScoreBandCitation)`
overload, beside its citation exactly as `putCitation(CdsCitation)` sits beside
`CdsCitation`: one file owns a citation and the words it puts on the wire.

**This read costs ZERO new statements.** The bands are columns on the `College`
row `StudentCollegeSelection` has already read, so `AdmissionsRows` gains no
field and `readInSession` stays at **SIX** statements — the absolute count
`CollegeAdmissionsServiceTest` asserts, and the count its KDoc claims. RFC 187's
own lesson applies in reverse here: the KDoc states a number, so the number is
asserted absolutely, and this slice must not move it.

### The copy — what the coach is allowed to say

`ScoreBandCopy`, beside `TestPolicyCopy`, owns every sentence:

- the band statement: _"the middle half of students admitted for autumn 2024
  scored between 620 and 720 on the SAT reading section"_ — the middle **half**,
  in words, because "25th to 75th percentile" is a statistician's phrase;
- the standing qualifier that rides with EVERY rendered band (the
  `CONFIRM_WITH_SCHOOL` pattern, and D17's reasoning): _"a quarter of admitted
  students scored below the lower number and a quarter above the higher one, so
  it is a picture of last year's class and not a cut-off"_;
- the D16 average sentence: _"the average score of students admitted for autumn
  2024"_, with _"an average, not a cut-off and not a target"_ attached;
- the absent-band sentence: _"we do not have score data for this school"_ — our
  gap, never a statement about the school (`POLICY_GAP_IS_OURS`'s rule).

No sentence anywhere says a student "needs" a score to be competitive, and no
sentence compares a student to the band. The tool DESCRIPTION carries the rules
that govern use: never turn a band into a chance of admission, never carry one
school's band to another school, never present the average as a threshold, and
say the cohort year with the numbers.

**A score band carries NO `FigureStatus` and no RFC 179 assurance tier** — the
spec's standing rule, and `SchoolTestPolicy`'s KDoc already states the reason:
that vocabulary belongs to the canonical money tables, whose rows record a
publisher's suppression and imputation flags. The Scorecard publishes none for
these columns.

### What this slice does NOT do

**It does not touch `prompts/coach-system-prompt.md`.** That file is frozen for
brief 0010 while the sibling run `rfc-192` (`concise/03/one-rule-one-home`) runs
a pure DELETION pass over it; a deletion rebased across a fresh addition can
silently drop a landed sentence with no test to catch it. Consequence, stated
rather than hidden — **prompt line 59 becomes false when this lands**:

> Each of those is a reason to go and check, never a fact about one of their
> schools: say plainly that you would have to check that school's own page,
> because scholarship score rules, athletics rules and **score ranges** are not
> things we hold.

After this slice we DO hold score ranges, for 1,039 schools. The replacement
sentence is recorded here for whichever slice next opens that file (`test/04` is
the natural one), and is decision **D-E**:

> ...because scholarship score rules and athletics rules are not things we hold.
> Score ranges we do hold, for the schools whose `score_bands` section comes
> back: say them from that section only, never from memory.

The tool DESCRIPTION mitigates in the meantime — it arrives in the model's
context attached to the section itself, which is the seam `TestPolicyCopy`
already argues is stronger than a rule stated once in the prompt.

### Alternative considered and rejected: read the bands from our own IPEDS ADM

The spec names the Scorecard fetch, and this RFC follows it, but the alternative
is real and was measured, so it is recorded rather than left for a reviewer to
re-derive. `db/seed/ipeds/adm2023.csv` — already fetched
(`bin/fetch-ipeds:258`), already loaded for `ADMCON7` — carries the same six
variables:

| source                                 | colleges with any band | cohort    |
| -------------------------------------- | ---------------------- | --------- |
| Scorecard most-recent institution file | 1,039                  | Fall 2024 |
| our own ADM2023, joined to `colleges`  | 1,071                  | Fall 2023 |

(1,001 schools in both, 38 Scorecard-only, 70 ADM-only.)

Reading from ADM instead would put the columns on `college_ipeds` — a table with
**no version trigger** and a **per-row `survey_year`** — so it would cost no
`log_college_version()` restatement and would need no year constant at all, and
the band would carry the SAME citation and the SAME year as the test policy
beside it. Against that: the data is a year older, the columns would sit on a
table whose grain is a survey year rather than a college, and the spec's
decision is Scorecard. Recorded as **D-F** for Ian to overrule if he wants it.

## Files Modified

- `db/schema/add-college-score-bands.sql` — NEW. The three statements above plus
  six `COMMENT ON COLUMN`.
- `db/schema/ORDER` — one appended line. The implementing run appends it, not
  `ship-order`: `bin/db-migrate` refuses an unordered `db/schema/*.sql`
  (`EXIT_SCHEMA_ORDER_DRIFT`), so no test could migrate a database without it,
  and `ship-order -h` is explicit that an unordered file's "position is the
  author's to declare". `ship-order` then RE-PLACES this run's own line
  deterministically after the final rebase, and no-ops when the file is already
  correct.
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` — six nullable `Int`
  fields on `College` and `NewCollege`.
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — row mapper, insert
  list, `EXCLUDED` list, full-row read, `NON_NULL_COUNTABLE_COLUMNS`.
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` — six
  `COL_` constants, `REQUIRED_INSTITUTION_COLUMNS`, the `ScoreBandVariable` enum
  the reads iterate, `NON_NULL_SUMMARY_COLUMNS`, `METHOD_VERSION` 9 -> 10.
- `college/src/main/kotlin/ed/unicoach/college/CsvIngestSupport.kt` —
  `intPairInDomainOrNull`, `CellPair`, `PairDrop`/`PairDropReason`, and
  `LoadCount.pairsDropped` with its line in `logSummary`. Added at review: the
  paired read is domain-agnostic and belongs beside its three siblings.
- `db/src/main/kotlin/ed/unicoach/db/models/College.kt` — also
  `PublishedScoreScale`, the ONE home of the three published scales (SAT
  section, ACT composite, SAT composite), read by the loader, by `ScoredTest`
  and by `AdmittedAverage`. The SQL CHECK remains the authority it mirrors.
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/SchoolScoreBands.kt`
  — NEW: `ScoreFamily`, `ScoredTest`, `ScoreBand`, `ScoreBandResult`,
  `MalformedBand`, `AdmittedAverage`, `SchoolScoreBands`, `ScoreBandCopy`,
  `ScoreBandWire`, `ScoreBandCitation`, and the
  `JsonObjectBuilder.putCitation(ScoreBandCitation)` overload. No `SatBands`:
  review collapsed it into one `bands` list (see the read section).
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/AdmissionsField.kt` —
  `SCORE_BANDS`, between `TEST_POLICY` and `DEADLINES`.
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsService.kt`
  — `CollegeAdmissions.scoreBands`, the `notReported` arm,
  `scoreBandsOf(college)`, KDoc (still six statements).
- `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsChatTool.kt`
  — `scoreBandsObject`, `NUMBERS_BY_CONTRACT` (D-C), `DESCRIPTION`.
- `db/seed/scorecard/FETCH-NOTES.md` — the dictionary cell for the six band
  variables, and why they are dated by a constant rather than by
  `dictionary-variable-sources.csv` (D-B).
- Test fixtures: `db/src/testFixtures/.../CollegeFixtures.kt`, the Scorecard
  loader CSV fixtures (all eight — one header change moves every one of them),
  and **every** `College`/`NewCollege` construction site in the repo, including
  `admin-web`'s `AdminTestSupport.kt` and the coaching test databases. The six
  fields are deliberately NOT defaulted, so the compiler enumerates those sites:
  that is what makes a forgotten field a build error at the seams — the loader
  and the version mirror — where it must be one. The reach is mechanical (six
  `= null` arguments per site) and touches no behaviour.
- **NOT** `prompts/coach-system-prompt.md` (frozen), **NOT**
  `db/seed/scorecard/dictionary-variable-sources.csv` (D-B), **NOT**
  `college_search_index`.

## Implementation Plan

1. Migration + `COMMENT ON COLUMN`s. Run `bin/db-reset` and prove a `colleges`
   write survives (the `42703` trap) before writing any Kotlin.
2. `College`/`NewCollege` + `CollegesDao` + fixtures; `:db` tests green.
3. Loader: constants, required columns, the paired domain reads (`readBand` /
   `ScoreBandCells` and the three stem coercion keys), summary columns,
   `METHOD_VERSION`. Note that the bump moves three existing assertions, and the
   `non_null` axis becomes sixteen keys (10 + 6).
4. `SchoolScoreBands` + `ScoreBandCopy` + the citation, with the D16 rule (D-A)
   expressed in `from(...)` so no call site can re-decide it.
5. `AdmissionsField`, the service field and the `notReported` arm, the chat-tool
   rendering and DESCRIPTION.
6. Full `nix develop -c bin/test` (no module, no `-f`), in a child, counts read
   from the JUnit XML `tests=` attribute.

## Tests

**The migration (`:db`, DB-backed) — the acceptance criterion the spec names.**

1. **The `42703` trap.** Insert a `colleges` row with all six bands set AFTER
   the migration, then update it; assert the write succeeds and that
   `colleges_versions` carries the six values for the prior version. This is the
   test that proves `log_college_version()` was restated; it fails with
   `42703 record "new" has no field` if it was not.
2. **Domains.** `sat_reading_25th_percentile_score = 199` and `= 801` rejected;
   `act_composite_75th_percentile_score = 37` and `= 0` rejected.
3. **Pairing and order.** A 25 with no 75 rejected; `75 < 25` rejected;
   `75 = 25` accepted.

**Ingest (`:college`).**

4. A fixture institution row with all six cells loads them; a row with blank
   cells loads six nulls; an out-of-domain cell is coerced to null, counted in
   `coercions`, and does not fail the run.
5. `REQUIRED_INSTITUTION_COLUMNS` contains all six (the existing coverage test
   shape), so a snapshot missing one fails before any write.

**The read (`:service`).**

6. A college with bands renders `score_bands` with both SAT sections, the ACT
   composite, the cohort year inside the sentence, and the "not a cut-off"
   qualifier; `SCORE_BANDS` is absent from `data_availability`.
7. A college with nothing renders no section and IS named in
   `data_availability`.
8. **D16:** a college with an SAT average and no SAT band renders the average,
   labelled as an average, with no fabricated band; a college with a band
   renders NO average. Both directions asserted, because the failure mode is
   showing both.
9. `readInSession` still costs **six** statements for a one-college and a
   five-college list — asserted absolutely against the named constant.
10. The RFC 143 numeric guard passes over the new payload (whatever D-C
    decides), and the wire-order test sees `score_bands` between `test_policy`
    and `deadlines`.
11. `ScoreBandCopy`'s sentences never contain "need", "cut-off" as a claim,
    "should score", or a chance-of-admission phrase — the D9 guard, asserted on
    the copy constants themselves. "cut-off" is checked by SHAPE and not by a
    ban list: the two sentences whose whole job is to deny it would be the first
    offenders of a flat ban, so every occurrence of the word must be the denial
    form ("not a cut-off"), and a future clause reading "their cut-off was 1400"
    fails. A separate case proves the phrase list alone cannot catch that
    sentence, so the two tests cannot be collapsed by a later reader.
12. The sweep is COMPLETE: reflection over `ScoreBandCopy`'s declared `String`
    fields must find no constant the swept list does not read. The list is
    hand-written because two sentences are functions, which makes it a second
    place to remember; this closes it.

## What review changed, and one lesson worth keeping

Three tiers of review moved this slice more than the first implementation did,
and the findings clustered: **a refusal must never be silent, and must never
quietly change what a family is told.** Four defects of that shape reached the
tree and were caught — the folded null, the unlogged refusal, the average
silently substituted for a range, and the one rendered figure with no scale
gate.

One lesson generalises past this slice, and it is the SECOND instance in this
brief: **the artifact under change cannot be its own witness.** Twice here a
guard was written that could not fail — a completeness sweep that compared a
reflected list with itself, and a drop-key assertion whose two conjuncts could
both go false. Both were mine, both passed, and both were found by reading the
guard rather than by running it. A test that derives its expectation from the
thing it is testing proves only that the thing equals itself.

A third of the same family, recorded because it nearly banked a false green: a
gate run piped into `tail` reports `tail`'s exit status, so `echo "EXIT=$?"`
printed `EXIT=0` over a `BUILD FAILED`. The number in this run's report comes
from an unpiped invocation.

## Open items, declined deliberately

- The loader still reads `SAT_AVG` as
  `intInDomainOrNull(..., 0, Int.MAX_VALUE, ...)`. The READ side now refuses an
  out-of-scale average, so no bad figure reaches a family, but the INGEST will
  still write one, uncounted, and `PublishedScoreScale.SAT_COMPOSITE` now sits
  right beside it. Tightening it is a behaviour change to a column this slice
  did not add.
- `CsvIngestSupport` accumulates coercions through a `MutableMap` parameter
  rather than returning them. That is the file's convention across three
  functions; converting one makes it the odd one out.
- 23 test files hand-build `NewCollege` instead of using `newCollegeFixture`.
  Pre-existing duplication this slice extends by six lines each.
- `putCitation` has two byte-identical overloads. They are identical only
  because both citations are url-less today; `CdsCitation` shows what happens
  when a citation has a per-school document.

## Decisions for the gate

Ian approved these at their pre-chosen defaults on 2026-09-12, verbatim:
**"approve both"**. They are written out here rather than left as references,
because an RFC is immutable once committed and a decision it names but does not
state cannot be reviewed against later.

**D-A — the D16 fallback reads per TEST FAMILY.** Prefer the band. Where a
school has an SAT **average** but no SAT **band**, say the average, labelled as
the average score of admitted students and explicitly not a cut-off and not a
target — and say it even when the school HAS an ACT band. Never an average and a
band for the same test; never a band fabricated from an average.

A THIRD state was found at review and ruled on by Ian as D16's author: a band we
hold and **cannot trust** is neither "a band" nor "only an average". It must not
promote the school into the average path, so a stored-but-unusable band yields
no section at all and the school is named in `data_availability`.

The reason is measured, and it is why the letter of spec D16 was not taken. D16
says "never show both for the same school", which reads naturally as "any band
suppresses the average". Under that reading the fallback would fire for **zero**
colleges: all 88 colleges with an SAT average and no SAT band have an ACT band,
and no college in the snapshot has an average and no band of any kind. The rule
D16 exists to serve is exactly those 88, so the rule is read per test family and
the SAT average stands in for the missing SAT band.

**D-B — the cohort is ONE named constant**,
`ScoreBandCitation.COHORT_TERM =
"autumn 2024"`, quoting the publisher's own
cell (`Most_Recent_Inst_Cohort_Map`: "Fall 2024, reported in IPEDS DCY2024-25"),
with the transcription recorded in `db/seed/scorecard/FETCH-NOTES.md`. The six
variables are NOT added to `dictionary-variable-sources.csv`:
`ScorecardDictionaryPinTest` asserts that file's key set EQUALS the
canonical-money fill's `LOADED_VARIABLES`, and its `Cohort` type parses
academic-year sentences only, never a Fall term. The term lives INSIDE the
sentence, so no bare numeric year reaches the wire.

**D-C — the wire carries BOTH numbers and sentences.** The three numeric keys
(`score_25th_percentile`, `score_75th_percentile`, `admitted_average_score`) are
admitted deliberately to `CollegeAdmissionsChatTool.NUMBERS_BY_CONTRACT` — the
first non-money figures in that set — because `test/06` and `test/07` will have
to compare a student's own stored score against the band, and a spoken sentence
cannot be compared. Each number is keyed by its measure, and each band carries
its statement beside it.

**D-D — the two implausible in-domain ACT bands are stored and rendered as
published** (Southeastern Baptist College 5-7, Conception Seminary College 1-5).
The ACT scale starts at 1, so no CHECK can exclude them, and a hand-picked
plausibility floor would be this slice's private opinion about a federal file,
applied silently to a family's answer.

**D-E — this slice does not touch `prompts/coach-system-prompt.md`.** The
replacement for the now-false "score ranges are not things we hold" sentence is
recorded above and belongs to `test/04` (RFC 197), which owns that file.

**D-F — the source is the College Scorecard**, six columns on `colleges`, with
the `log_college_version()` restatement. The IPEDS ADM alternative recorded
above was put to Ian and not taken.

**D-G — the six columns join the ingest change summary** and `METHOD_VERSION`
goes 9 -> 10.
