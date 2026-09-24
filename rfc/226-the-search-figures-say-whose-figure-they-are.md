# RFC 226 — The search figures say whose figure they are, and about whom

Product slice: `beyond/11/stamp-the-search-figures` (brief 0011 — beyond money).
Lane A. **No new table, no migration, no new data source.**

## Context

Brief 0011 D47 is standing and binding:

> A figure a family can see carries the publisher that filed it and the period
> it describes, all the way to the sentence that speaks it. A DERIVED figure
> carries the **oldest** vintage among its inputs. A figure we author ourselves
> still names a publisher — us.

Four outcome figures ride every search row and carry none of that:

| Wire key                           | Column                                                 | Stamp today                     |
| ---------------------------------- | ------------------------------------------------------ | ------------------------------- |
| `undergrad_enrollment_headcount`   | `colleges.undergrad_enrollment_headcount` (`UGDS`)     | none                            |
| `admission_rate_share`             | `colleges.admission_rate_share` (`ADM_RATE`)           | none                            |
| `completion_rate_150pct_4yr_share` | `colleges.completion_rate_150pct_4yr_share` (`C150_4`) | none                            |
| `pell_share`                       | `cohort_money_stats` measure `pell_share` (`PCTPELL`)  | stored, and stored as `undated` |

The first three are absent from
`db/seed/scorecard/dictionary-variable-sources.csv` (24 rows, all money) and
from `ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE`. They are the three
this RFC stamps. `PCTPELL` is dated by the publisher to `AcadYr 2023-24` in that
file and is nevertheless stamped `CanonicalMoneyLoader.VINTAGE_UNDATED` by the
fill — RFC 183 D3 declared that gap and `ScorecardDictionaryPinTest` pins it so
that whoever closes it has to say so. §5's measurement shows that gap cannot be
closed by this run either, so `pell_share`'s stamp is reported as a separate
defect and this RFC does not close it.

And the year is not the hard part. `C150_4` is a **completion** rate over a
population most families do not assume: full-time, first-time,
degree/certificate-seeking undergraduates at four-year institutions, measured at
150% of normal time. "93% graduate" is false; "of the students who started
full-time for the first time in autumn 2018, 93% had finished within six years"
is true. The population is the load-bearing half of the sentence, so this RFC
treats it as a first-class part of the stamp rather than a footnote.

**The slice's `CONFLICTS` edge is spent, not dormant.** `beyond/01` was
specified as the guardrail that would forbid quoting these unstamped figures,
and this slice was specified as the one that makes three of them quotable — two
writers of the same rule. RFC 219 (the `beyond/01` run) dropped that figure rule
entirely at its approval gate: suppression was the wrong instrument, and the fix
moved here. RFC 219 now writes only a two-line prediction rule and opens no
search figure, no tool key and no copy this RFC touches. There is nothing left
for the two to collide over, so this run reads no warning from it. The only
remaining contact is the ordinary shared-file rebase on
`prompts/coach-system-prompt.md` that any two prompt-editing runs have, and
brief 0011's corrected parallel-work protocol already clears that (git merges
text; the second lander reads the first's paragraph).

## The publisher's own dictionary, read for this RFC

`CollegeScorecardDataDictionary.xlsx` was downloaded from
<https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx> on
2026-09-16: **727,278 bytes**, sha256
`2f314d1d02d4ce7e8ca29feffaaa69860b18b2812cbb407adc014c6792fa1740` — the same
byte count `db/seed/scorecard/FETCH-NOTES.md` records for the download the 24
existing rows were transcribed from. Sheet `Most_Recent_Inst_Cohort_Map`, column
`Cohort/Measure in "Most Recent" Datafile`, verbatim:

| Variable   | Cell                                                                                                   |
| ---------- | ------------------------------------------------------------------------------------------------------ |
| `ADM_RATE` | `Fall 2024, reported in IPEDS DCY2024-25`                                                              |
| `UGDS`     | `Fall 2024, reported in IPEDS DCY2024-25`                                                              |
| `C150_4`   | `Fall 2018 or AcadYr 2018-19 cohort (differs by institutional calendar), reported in IPEDS DCY2024-25` |
| `PCTPELL`  | `AcadYr 2023-24, reported in IPEDS DCY2024-25`                                                         |

Sheet `Institution_Data_Dictionary` gives `SOURCE = IPEDS` for all four, and its
`NAME OF DATA ELEMENT` / `NOTES` columns are where the POPULATION is written
down — quoted in full in `ScorecardOutcomeCitations`' KDoc and summarised here:

- `ADM_RATE` — "Admission rate". The dictionary states no population note: it is
  one overall rate for the entering class, with no split by application round.
- `UGDS` — "Enrollment of undergraduate certificate/degree-seeking students".
- `C150_4` — "Completion rate for first-time, full-time students at four-year
  institutions (150% of expected time to completion)"; the note adds
  "degree/certificate-seeking" and that 150% of normal time "is typically 6
  years" for a bachelor's.
- `PCTPELL` — "Percentage of undergraduates who receive a Pell Grant"; the note
  adds that the denominator is ALL undergraduates, over a fall or a full-year
  enrolment cohort depending on the school's calendar.

**This is a transcription, exactly like the 24 rows beside it.** There is no
xlsx reader on this project's JVM classpath and RFC 173 declined to add one;
what makes a transcription checkable is that it is committed, digested and
pinned, which is what happens below.

## Detailed Design

### 1. The transcription stops being the money seam's private pin

`db/seed/scorecard/dictionary-variable-sources.csv` gains three rows, with the
publisher's own `source` and the publisher's own cohort clause:

    ADM_RATE,IPEDS,Fall 2024
    C150_4,IPEDS,Fall 2018 or AcadYr 2018-19 cohort (differs by institutional calendar)
    UGDS,IPEDS,Fall 2024

Two facts about that spelling, both deliberate:

- The existing 24 cells hold the publisher's LEADING clause — `AcadYr 2024-25`,
  not `AcadYr 2024-25, reported in IPEDS DCY2024-25`. The three new cells follow
  the file's own convention, and `FETCH-NOTES.md` is corrected to say "the
  cohort clause of the cell, up to the `, reported in` the sheet appends to
  every row" instead of the "as the publisher writes it" it says today, which
  was never true of any of the 24.
- `C150_4`'s cell contains a comma, so it is quoted by the CSV writer. The
  reader is `commons-csv` with a default format and already handles that; a test
  asserts the round trip, because a hand-edited quoted cell is exactly the kind
  of thing that reads as two columns.

**Why the CSV and not a Kotlin constant — and what this RFC supersedes.** RFC
195 D-B decided to keep non-money dates in Kotlin: it declined to put the six
score-band variables here, and gave two reasons. **This RFC partly supersedes
that decision.** For the three OUTCOME figures it removes both of those reasons,
knowingly; the six score-band variables and `SAT_AVG` stay in Kotlin exactly as
RFC 195 D-B left them. The supersession is written down rather than left
implicit, because RFC 195 D-B is a committed decision and a later RFC that
reverses half of it must say which half:

1. _"`ScorecardDictionaryPinTest` asserts that file's key set EQUALS the
   canonical-money fill's `LOADED_VARIABLES`"_ — it does, and that is the
   assertion this RFC widens. The file's contract becomes: **every Scorecard
   column whose date we cite gets one dated row here**, which is the union of
   the money fill's registry and the new outcome registry below. The
   `VINTAGE_GROUP_BY_VARIABLE` map is NOT widened: it is the money fill's
   statement of which year IT stamps a cell with, and an outcome column it never
   writes has no business in it (brief 0011 D36/D37 — model each thing as what
   it is; never deform an existing model to absorb something new).
2. _"its `Cohort` type parses academic-year sentences only, never a Fall term"_
   — `Cohort` gains a third arm, `StatedTerm(term: String, autumnYear: Int)`,
   for a cell that is neither an `AcadYr` sentence nor empty. The cell is
   carried verbatim, and its fall year is parsed ONCE, at the read boundary, for
   the pin test only: an entering TERM, an academic YEAR and the year a figure
   DESCRIBES are three different things, and RFC 183 found two Scorecard stamps
   wrong by two years by mixing them. The reader refuses a cell that names no
   fall year rather than carrying it as a term.

The six score-band variables and `SAT_AVG` do NOT join in this run. They are
dated from the same workbook by `ScoreBandCitation.COHORT_TERM`, pinned by
`ScoreBandCitationPinTest` against `PROVENANCE.json`, and folding them in is a
second decision with its own test to write. `FETCH-NOTES.md` records that RFC
195 D-B's two stated reasons are now spent FOR THE OUTCOME FIGURES — the part
this RFC supersedes — and that the remaining reason for the score bands is
simply that nobody has done it.

### 2. One registry for the outcome columns, beside the money one

`ScorecardInstitutionColumns` — already the ONE home both loaders read the file
through — gains `ADM_RATE`, `C150_4` and `UGDS` constants (moved off
`CollegeScorecardLoader`'s private literals, so the name is stated once) and:

```kotlin
/**
 * The Scorecard columns the `colleges` fill writes that a FAMILY is shown as a
 * figure, each dated by `dictionary-variable-sources.csv`. Not money, so not in
 * [VINTAGE_GROUP_BY_VARIABLE]; dated all the same, because brief 0011 D47 dates
 * every figure a family can see and not only the money ones.
 */
val OUTCOME_VARIABLES: Set<String> = setOf(ADM_RATE, C150_4, UGDS)
```

`ScorecardDictionaryPinTest`'s key-set assertion becomes
`LOADED_VARIABLES + OUTCOME_VARIABLES`, and a SECOND test beside it asserts the
two sets DISJOINT — a column that is both money and outcome would be dated twice
by two owners and the file would not say which won. Two tests rather than one,
on this suite's own habit: the union and the disjointness are two claims, and a
separate test names in its own title which of them broke.

`AssuranceTierClosureTest` reads the same file's `source` column and requires a
tier for every row, so the three rows join `AssuranceTier.SCORECARD_TIERS` as
`MANDATORY_SURVEY` — which is what IPEDS is. Their hard-coded counts move 24→27
and 22→25, and the committed digest moves in BOTH places that hold it
(`FETCH-NOTES.md` and the constant beside the closure test).

### 3. `ScorecardOutcomeCitations` — the sentence, once

A new object in `college/src/main/kotlin/ed/unicoach/college/`, modelled on
`ScoreBandCitation` (RFC 195 D-B, `putCitation`) because that is this repo's
answer to the same problem one layer over. It lives in `:college`, NOT in
`:service`: the two tools that emit `figure_sources` are in `:college`, and
`:college` cannot see `:service` types; `:service`'s `FitLensService` reads it
fine in the other direction.

```kotlin
object ScorecardOutcomeCitations {
  data class Citation(
    val wireKey: String,
    val variable: String,             // the publisher's column, for the pin test only
    val citedAs: String,              // the WHOLE sentence fragment the coach says
    val present: (CollegeMatch) -> Boolean,
  )
}
```

One entry per wire key, and `citedAs` is the single source of truth: the
publisher, the population and the period all live inside it, so a sentence
cannot contradict its own period and no bare numeric year reaches the wire. The
fragments, in full:

- `completion_rate_150pct_4yr_share` — "the share of students who started
  full-time, for the first time, in autumn 2018 and had finished a degree at
  that school within six years, as the U.S. Department of Education's College
  Scorecard reports it"
- `admission_rate_share` — "the share of applicants that school admitted for the
  class entering in autumn 2024, as the U.S. Department of Education's College
  Scorecard reports it — one overall rate, not a rate for any single application
  round"
- `undergrad_enrollment_headcount` — "the number of undergraduates seeking a
  degree or certificate there in autumn 2024, as the U.S. Department of
  Education's College Scorecard reports it"

`pell_share` is deliberately NOT one of them, per §5: the search serves it from
TWO sources at two vintages, so one page-level sentence would be true of some
rows and false of others.

Four rules the object enforces, each by construction or as a test:

- **`citedAs` is the one copy of the period and the population**: no `period` or
  `population` field exists to contradict it. A pin test maps each
  `Citation.variable` to its dictionary cell and asserts the sentence contains
  the term that cell states (`autumn 2018` for `Fall 2018 …`, `autumn 2024` for
  `Fall 2024`), so a snapshot that re-dates any of the three fails there.
- **The snapshot release is NOT re-stated here.**
  `ScorecardFieldOfStudyColumns.COHORT_SNAPSHOT_RELEASE` is this module's one
  transcription of the committed seed's `release`, and
  `ScorecardFieldOfStudyPinTest` already pins it against `PROVENANCE.json`. An
  alias on this object would be a second name for that constant with no reader,
  so the object carries none: a re-fetch that moves the data still fails that
  pin test, which is where the guard belongs.
- **The publisher is named in the fragment**, never in a trailing list — the
  same rule RFC 198 put in the prompt.
- **The closed set is enumerated once**: each citation carries its own `present`
  predicate, so the registry and the presence rule cannot drift apart.

`ScoreBandCitation` is NOT reached into and NOT generalised. One file owns a
citation and the words it puts on the wire (its own KDoc says so); two sections
sharing a citation object is how a testing fact ends up inside the money
vocabulary.

### 4. The wire: one `figure_sources` block per ANSWER, keyed by figure

`search_colleges` and `similar_colleges` gain ONE page-level key beside the
existing `source_years` and `excluded_unknown` blocks:

```json
"figure_sources": {
  "admission_rate_share":             { "cited_as": "…" },
  "completion_rate_150pct_4yr_share": { "cited_as": "…" },
  "undergrad_enrollment_headcount":   { "cited_as": "…" }
}
```

Per answer, not per row, and the reason is a fact rather than a byte count:
**these three stamps are properties of the SNAPSHOT, not of the college.** Every
row in the corpus carries the same publisher and the same cohort for these
three, so a per-row copy would state one fact 25 times and invite the reader to
believe it could differ between two rows. The key IS the figure's own wire key,
so the stamp is not "away from its figure" in RFC 198's sense — that rule is
about a coach's SENTENCE, and the prompt paragraph below keeps it. `pell_share`
is not in the block for the §5 reason: its source and vintage vary per college,
which is exactly what a page-level snapshot stamp cannot state.

A key is present only when at least one returned row carries that figure, on
`source_years`' own precedent: an answer whose rows all withheld the completion
rate cites no completion-rate cohort.

The four numeric keys are untouched. No key is removed from any payload; the
sorts and filters read them exactly as before.

`FitLensService`'s prose path (which a payload never reaches) already explains
`netPriceBasis` / `netPriceVintage` in words at the same seam; the three outcome
figures it speaks there take their fragment from the same object, so the two
paths cannot drift.

### 5. `PCTPELL`: the stored stamp is NOT changed, because the measurement said so

`CanonicalMoneyLoader` stamps `pell_share` `VINTAGE_UNDATED` while the Scorecard
dictionary dates `PCTPELL` to `AcadYr 2023-24`. The decision on the table was to
move it onto the dated group it belongs to, but only after measuring who else
writes the key, because `cohort_money_stats` is unique on a key that includes
`vintage` and moving a row from `undated` to `2023-24` changes which row wins
that key IF a second source also writes `pell_share`.

The measurement ran against the worktree dev database after a real fetch and
ingest (Scorecard institution + fields pair, plus `sfa2223.csv`):

    SELECT source, source_variable, vintage, count(*)
      FROM cohort_money_stats WHERE measure = 'pell_share' GROUP BY 1,2,3;

    ipeds_sfa|upgrntp|2022|5415
    scorecard|PCTPELL||6273

The Scorecard is NOT the only writer. IPEDS SFA writes `pell_share` (`upgrntp`,
aid year 2022-23) at the SAME address, 5,415 rows beside the Scorecard's 6,273
undated `PCTPELL` rows. The search lateral serves newest vintage first, so the
search wire's `pell_share` is the SFA 2022-23 figure wherever SFA data exists.

**Both the stored-stamp change and the spoken stamp are therefore DROPPED from
this run** (Ian's decision 2, "if not, drop that part of the run and report
it"). `PCTPELL` stays in the `UNDATED` vintage group and RFC 183 D3's pin test
stays exactly as it is. §3's first draft gave `pell_share` one SFA sentence, but
the measurement kills that too: the search serves the SFA's 2022-23 figure where
SFA data exists and the Scorecard's undated `PCTPELL` otherwise, so a single
page-level sentence would be true of some rows and false of others.
`figure_sources` therefore omits `pell_share`, and the prompt does not licence
speaking its period. The finding is reported as a separate defect: where SFA
data exists the search serves a Pell share one aid year older than the
publisher's current figure, because the newer Scorecard `PCTPELL` is stored
undated and loses to the older SFA row — and where SFA data is absent the served
figure is that undated Scorecard cell, which still carries no stamp.

### 6. The prompt: the coach may speak these three, WITH their population

One new paragraph in `prompts/coach-system-prompt.md`, registered in the
`CoachParagraph` enum, one sentence per line. It says what is not already there:

- these three figures may be spoken as figures, because they now carry a stamp;
- the words — population and period included — come from
  `figure_sources.<key>.cited_as` in the tool result and are never assembled
  from memory;
- a worked example shows the SHAPE of a correct completion-rate sentence — which
  students were counted, when they started, and the window they had to finish in
  — and carries **no literal period and no literal share**;
- a completion rate is never a bare graduation percentage, an admission rate is
  not this student's chance of admission (the odds rule already in the testing
  block still governs), and enrolment is a size, not a quality.

**The example ships with its facts stripped out, and that is the decision, not
an omission.** This RFC's §1 argues the population case with a concrete pair
("93% graduate" against the full sentence), and an earlier draft of this section
put that pair into the prompt verbatim. It does not ship, for two reasons that
are this slice's own thesis turned on itself. A period typed into the prompt is
a SECOND copy of the one fact `figure_sources` exists to own, so the next
Scorecard snapshot would leave the prompt stating a period that contradicts the
stamp the same paragraph tells the coach to read. And a concrete invented share
sitting in a system prompt is a figure a model can surface in an answer about a
real school. The shape is what teaches; the numbers were what would rot.

So the paragraph names the three parts of the sentence rather than quoting one,
and phrases the contrast as "never a bare graduation percentage" rather than by
quoting a fake figure. The example is also conditioned on the entry being
present, so it cannot contradict the rule two lines above it that an unstamped
outcome figure is never dated and never attributed.

`pell_share` is not licensed by this paragraph (§5): its stamp waits on the
stored-stamp fix. The paragraph therefore says SOME outcome figures arrive with
an entry, never all of them, and it states outright that an outcome figure
without one carries no stated period or population — with the scope confined to
outcome figures, because the academic years the tool gives for the other figures
it returns are dated by `source_years` and are not this paragraph's business.

The live pin is read from `service/src/main/resources/service.conf` at
implementation time and the new version is minted by `bin/prompt-seed` at land;
no version literal is typed into this RFC. Rollback is PARTIAL: dropping the pin
to the previous version removes the paragraph and leaves the payload key in
place, which is a coach that has the stamp and does not use it — safe.

## Files Modified

| Path                                                                              | Change                                                                                 |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `db/seed/scorecard/dictionary-variable-sources.csv`                               | +3 transcribed rows                                                                    |
| `db/seed/scorecard/FETCH-NOTES.md`                                                | new digest; the cohort-clause convention; the RFC 195 D-B note                         |
| `college/src/main/kotlin/ed/unicoach/college/ScorecardInstitutionColumns.kt`      | `ADM_RATE`/`C150_4`/`UGDS` constants, `OUTCOME_VARIABLES`                              |
| `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`           | reads the three column names from the registry                                         |
| `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`             | NOT changed — §5's measurement dropped the `PCTPELL` change                            |
| `db/src/main/kotlin/ed/unicoach/db/models/AssuranceTier.kt`                       | three IPEDS rows join `SCORECARD_TIERS`                                                |
| `college/src/test/kotlin/ed/unicoach/college/ScorecardDictionaryTranscription.kt` | `Cohort.StatedTerm`                                                                    |
| `college/src/main/kotlin/ed/unicoach/college/ScorecardOutcomeCitations.kt`        | NEW — the three citations, the dictionary quotes they transcribe, per-figure `present` |
| `college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt`                  | the three wire keys, `putFigureSources`, `FIGURE_SOURCES_NOTE`                         |
| `CollegeSearchTool.kt`, `SimilarCollegesTool.kt`                                  | emit the block; descriptions name the new key                                          |
| `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt`          | the three outcome figures take their words from the citation object                    |
| `prompts/coach-system-prompt.md`                                                  | one new paragraph                                                                      |
| tests                                                                             | below                                                                                  |

No migration. No `db/schema/ORDER` line. No new table, so nothing for brief 0001
D10 to gate.

## Implementation Plan

1. Measure §5's query against the test database; record the result in the run
   scratch. (DONE — two writers; the `PCTPELL` stored-stamp change is dropped.)
2. CSV rows + `FETCH-NOTES.md` (convention sentence, digest, RFC 195 D-B note) +
   the digest constant beside `AssuranceTierClosureTest`.
3. `Cohort.StatedTerm` and the widened key-set assertion; `OUTCOME_VARIABLES`;
   the three column constants; `SCORECARD_TIERS` rows and the moved counts.
4. `ScorecardOutcomeCitations` and its pin tests (the three citations, the
   per-figure `present` predicate).
5. `ScorecardOutcomeCitations.presentIn` + `putFigureSources` and the two tools;
   `FitLensService` asks the same function which figures are present, so the
   prose path and the payload path share one enumeration and not merely one
   vocabulary.
6. The prompt paragraph and its `CoachParagraph` registration.

## Tests

- `ScorecardDictionaryPinTest` — the key set equals the UNION of the two
  registries; the two registries are disjoint; the three new cells parse as
  `StatedTerm` and carry the publisher's exact clause, which IS the round-trip
  assertion for the quoted `C150_4` cell, made against the COMMITTED file: a
  cell read as two columns cannot carry the clause whole.
- `AssuranceTierClosureTest` — 27 rows, 25 IPEDS, new digest, every row tiered.
- `ScorecardOutcomeCitationsTest` — one citation per wire key and no others
  (three, with `pell_share` deliberately absent); every fragment names the
  publisher; every fragment's period is the term its dictionary cell states, the
  CSV-derived term asserted inside the sentence so the period is not typed
  twice; `C150_4`'s fragment contains BOTH "full-time" and "for the first time",
  which is the population claim this RFC exists to make. The snapshot release
  stays pinned where it already is, by `ScorecardFieldOfStudyPinTest`, and this
  object states no second copy of it.
- `CollegeSearchTool` / `SimilarCollegesTool` — `figure_sources` appears with
  exactly the keys the returned rows carry; absent for a figure no row reports;
  every numeric key still present (the slice's "no figure key is removed").
- `CanonicalMoneyLoader` — NOT changed: §5's measurement found a second writer,
  so `PCTPELL` stays undated and RFC 183 D3's pin test is untouched.
- `SystemPromptCatalogTest` — the paragraph is registered, its opener matches
  exactly once in the served body, the served body equals the authored file, and
  the previous version is still selectable.
