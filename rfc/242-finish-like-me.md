# RFC 242 — Finish like me: the eight-year completion figure, its Pell split, and transfer-out

**Slice:** `beyond/06/finish-like-me` (brief 0011, beyond money). **Lane:** A.
New ingest columns, new published figures, new family-facing copy. **Decisions
it carries:** brief 0011 **D9** (the honest headline), **D7** and **D47** (every
figure carries its publisher and the period it describes), **D23** (the caveat
budget), **D48** (the subgroup cut is Pell, not an income third — Ian,
2026-09-25), and brief 0001 **D10/D11/D12**.

## Why

The figure unicoach serves today for "do students finish there?" is `C150_4`:
the share of students who started **full-time, for the first time**, at a
four-year school and finished **at that same school within six years**. It is
the number the sector quotes, and for a family asking "will someone like me
finish?" it is the wrong number twice over. It excludes part-time students and
transfer-ins — most of the students who are not the traditional case — and it
counts a student who transfers and graduates elsewhere as a failure.

The publisher already publishes the better measure and the split this slice
needs:

| Variable             | What it counts                                                                                                             |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `OMAWDP8_ALL`        | all entering students — full-time or part-time, first-time or transferring in — who earned an award within **eight years** |
| `OMAWDP8_PELL_ALL`   | the same measure, for students who received a federal Pell grant                                                           |
| `OMAWDP8_NOPELL_ALL` | the same measure, for students who did not                                                                                 |
| `TRANS_4`            | the share of the `C150_4` cohort who transferred out instead of finishing there                                            |

None of the four is read by any code in this repo today. The loader's outcome
set is `setOf(ADM_RATE, C150_4, UGDS)`
(`college/src/main/kotlin/ed/unicoach/college/ScorecardInstitutionColumns.kt:226`).

### What the data actually says, measured before the design

Measured over the pinned institution snapshot (6,273 rows) during design, not
assumed:

- `OMAWDP8_ALL` is present on **3,542** rows against `C150_4`'s **2,263**.
  **1,290 schools gain a completion figure** they have none for today; **11
  lose** one.
- The difference between the two measures moves **both ways** — median
  **+0.024**, range **-0.909 to +0.668**. This is the evidence for D9's
  requirement that the change is explained as a different measure of a different
  population and **never as improvement**.
- The Pell arms are **not** co-present with the headline: 106 rows carry the
  all-student and non-Pell figures with no Pell figure, and 6 the reverse. A
  CHECK constraint requiring the pair would reject 112 real rows. They are
  independently absent, so they are not a pair.
- **The Pell figure is lower than the non-Pell figure on only 65.8% of rows.**
  Copy that says Pell students finish less often _is wrong about one school in
  three_. The coach says the two numbers; it does not narrate a gap.
- All four are shares in `[0,1]`; the only silence token present is `NA` — zero
  `PS` (publisher suppression) and zero blanks, so the existing `RATE_MIN` /
  `RATE_MAX` domain and the `BETWEEN 0 AND 1` CHECK apply unchanged.
- `TRANS_4` is exactly co-present with `C150_4`, so it inherits the same
  four-year, first-time, full-time gap.

## The period each figure describes — the hard stop, settled first

D7 and D47 are binding: a figure a family can see carries the publisher that
filed it and the period it describes, all the way to the sentence that speaks
it. RFC 183 fixes HOW: "the stamped year equals the year the publisher's own
dictionary states — pinned by test against the committed dictionary, not a
literal re-typed in Kotlin."

`db/seed/scorecard/dictionary-variable-sources.csv` holds no row for any of the
four. The cells come from one artifact, `CollegeScorecardDataDictionary.xlsx`,
which is deliberately **not** in this repo and which no script in `bin/` fetches
— RFC 173, 183 and 226 each affirmed that, and this RFC does not reverse it.

**So this run reads the workbook once, by hand, exactly as RFC 226 did** — one
deliberate download, four `SOURCE` cells and four leading
`Cohort/Measure in "Most Recent" Datafile` clauses transcribed into the CSV, and
the download's URL, date, byte count and sha256 recorded in
`db/seed/scorecard/FETCH-NOTES.md`. No new dependency, no xlsx reader, no
build-time fetch.

**Two ways this could still have failed, and what happened.** The transcription
reader accepts an empty cell, `AcadYr <label>` or `Fall <yyyy>` and nothing
else, and the `AcadYr` arm rejects any trailing clause. `OMAWDP8*` is an IPEDS
Outcome Measures full-year entering cohort, so its cell was expected to be an
academic-year one, for which there was **no spoken form** — the citation test
required a stated term and "autumn" would misstate a July-June cohort.

**What the workbook actually says.** Read once on 2026-09-25 (§ below), sheet
`Most_Recent_Inst_Cohort_Map`, leading clause only:

| Variable             | `SOURCE` | `Cohort/Measure in "Most Recent" Datafile`, leading clause               |
| -------------------- | -------- | ------------------------------------------------------------------------ |
| `OMAWDP8_ALL`        | `IPEDS`  | `AY 2016-17 cohort`                                                      |
| `OMAWDP8_PELL_ALL`   | `IPEDS`  | `AY 2016-17 cohort`                                                      |
| `OMAWDP8_NOPELL_ALL` | `IPEDS`  | `AY 2016-17 cohort`                                                      |
| `TRANS_4`            | `IPEDS`  | `Fall 2018 or AcadYr 2018-19 cohort (differs by institutional calendar)` |

(The full cell in every case appends `, reported in IPEDS DCY2024-25`, which is
the collection year and is dropped by the leading-clause convention.)

So the hazard fired, in a THIRD shape the reader did not have: not `AcadYr`, not
`Fall`, but `AY <label> cohort`. Two consequences, both paid in this run:

- **`TRANS_4` needed nothing.** Its cell is `C150_4`'s own, byte for byte — it
  counts that same cohort — so it goes through the existing `Fall` arm and is
  spoken "autumn 2018". It is still transcribed as its own row and carries its
  own cohort constant: the one-constant-per-cell rule forbids the fold, and a
  future snapshot may re-date one without the other.
- **The three `OMAWDP8_*` cells forced a new reader arm and a new spoken form.**
  `ScorecardDictionaryTranscription` gains `Cohort.StatedEnteringAcademicYear`,
  read by `matchEntire` on `AY <label> cohort` with the label held to
  `AcademicYear.label` — so a trailing clause, a four-digit second year, or a
  dropped `cohort` are refusals, not silent successes. It is DISTINCT from
  `Cohort.StatedAcademicYear`, which is how the sheet spells a MONEY collection
  year: the year a cohort entered and the year a figure was collected for are
  different facts, and RFC 183 exists because two Scorecard stamps were wrong by
  two years over exactly that confusion. The spoken form is **"the 2016-17
  academic year"**, never "autumn 2016" — a cohort that arrived across a
  July-June year did not arrive in the autumn, and it still contains exactly one
  four-digit run, so the citation test's one-period rule holds unchanged in
  shape. `ScorecardOutcomeCitationsTest`'s `assertIs<Cohort.StatedTerm>` becomes
  an exhaustive `when` over `Cohort`, so a fifth cohort shape is a compile error
  there rather than a silent pass.

**Nothing was dropped.** `TRANS_4` did not have to go and the Pell arms were
never at risk. The fallback rule stands unused and unchanged: had the work been
cut, `TRANS_4` would have gone first and the Pell arms never — they are D48's
whole answer. A figure whose period cannot be transcribed is not served.

**The download, recorded.**
<https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
read **2026-09-25**, **727,278** bytes, sha256
`2f314d1d02d4ce7e8ca29feffaaa69860b18b2812cbb407adc014c6792fa1740`. That digest
is **identical** to the one RFC 226 recorded on 2026-09-05: it is the same
artifact, byte for byte. So the WORKBOOK digest in `FETCH-NOTES.md` and in
`AssuranceTierClosureTest` does not move; what moves is the transcription CSV's
own digest, which is a different hash and the one the closure test recomputes.
`FETCH-NOTES.md` records this as a SECOND dated read rather than replacing RFC
226's line, because "the two reads are byte-identical" is a fact only two dates
can state.

## Detailed Design

### 1. Storage — four columns on `colleges`, not a new table

`colleges` is the home. `college_ipeds` is a different publisher keyed by
`survey_year` and is unversioned (`db/schema/0055…:15-68`); `cohort_money_stats`
is money by CHECK (`db/schema/0083…:263-276`) and would need `measure`,
`population`, `aid_scope` and `vintage` all widened plus a money-registry entry,
to store a figure that is not money. The cost of `colleges` is that it records
no per-cell suppression status — the `colleges` fill has ONE silence arm, unlike
the canonical money fill's three. **Measured, that costs nothing on this
snapshot**: zero `PS` and zero blanks in all four columns. It is a named,
bounded debt, not a defect introduced here.

Migration `add-eight-year-completion-and-transfer-out.sql` (a kebab slug, no
number — RFC 180; its `db/schema/ORDER` line is written by `ship-order` at
land), on the three-part `add-college-score-bands.sql` template:

```sql
ALTER TABLE colleges
    ADD COLUMN completion_rate_8yr_share          DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_pell_share     DOUBLE PRECISION NULL,
    ADD COLUMN completion_rate_8yr_non_pell_share DOUBLE PRECISION NULL,
    ADD COLUMN transfer_out_rate_4yr_share        DOUBLE PRECISION NULL,
    ADD CONSTRAINT colleges_completion_rate_8yr_share_range_check
        CHECK (completion_rate_8yr_share IS NULL
               OR completion_rate_8yr_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_completion_rate_8yr_pell_share_range_check
        CHECK (completion_rate_8yr_pell_share IS NULL
               OR completion_rate_8yr_pell_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_completion_rate_8yr_non_pell_share_range_check
        CHECK (completion_rate_8yr_non_pell_share IS NULL
               OR completion_rate_8yr_non_pell_share BETWEEN 0 AND 1),
    ADD CONSTRAINT colleges_transfer_out_rate_4yr_share_range_check
        CHECK (transfer_out_rate_4yr_share IS NULL
               OR transfer_out_rate_4yr_share BETWEEN 0 AND 1);
-- the same four on colleges_versions (no CHECK: a version row mirrors history)
-- CREATE OR REPLACE log_college_version() to carry them, or the trigger raises
-- 42703 on the first write (the trap the template documents)
```

The constraints are NAMED and each carries an explicit `IS NULL OR` arm. A
`CHECK` is satisfied by `UNKNOWN`, so the arm changes no behaviour — it states
the nullability claim instead of leaving it to a reader's knowledge of SQL's
three-valued logic, and the name is what a later drop, or a refused insert, has
to quote.

**No pair CHECK between the Pell arms** — measured, 112 real rows carry one
without the other.

**No `college_search_index` columns.** These are returned context, not filters
or sorts; both search statements already join `colleges`
(`CollegesDao.kt:851,1480`). That removes index DDL, `insertIndexRows` and the
sort-word-list test from the change set. The existing completion filter and sort
keep reading `completion_rate_150pct_4yr_share` and are not re-pointed.

**The provenance half.** The four columns join
`CollegesDao.NON_NULL_COUNTABLE_COLUMNS`, from which
`CollegeScorecardLoader.NON_NULL_SUMMARY_COLUMNS` derives, so the
`college_index_build.change_summary` `non_null` axis moves from 16 keys to 20
and `CollegeScorecardLoader.METHOD_VERSION` moves 10 -> 11. That bump is not
optional: a version-10 row and a version-11 row carry different key SETS, and
without it a reader cannot tell a run that found no eight-year figures from a
run that never looked for them. Three test literals move with it —
`CollegeScorecardIngestTest`, `CdsSeedLoaderTest` and `IpedsIngestTest` — and
this is the axis the Implementation Plan's step-3 census check reads.

### 2. Ingest — four copies of one existing block

`OUTCOME_VARIABLES` (`ScorecardInstitutionColumns.kt:226`) widens from three to
seven; `DATED_VARIABLES` derives from it. Four `const val`s, four `COL_*`
aliases, four `REQUIRED_INSTITUTION_COLUMNS` entries (so a snapshot that drops a
column fails the header assertion rather than silently serving nulls), and four
`doubleInDomainOrNull(record, COL_X, RATE_MIN, RATE_MAX, "<colleges column>",
coercions)`
calls beside `CollegeScorecardLoader.kt:1660-1668`.

### 3. The period — four rows in the committed transcription

Four rows added to `db/seed/scorecard/dictionary-variable-sources.csv` from the
publisher's workbook, its digest updated in **both** places that hold it
(`db/seed/scorecard/FETCH-NOTES.md` and
`AssuranceTierClosureTest.DICTIONARY_SHA256`), four `SCORECARD_TIERS` entries,
and the two `AssuranceTier` counts moved 27 -> 31 and 25 -> 29.

### 4. Serving — four more citations, and nothing else

The serving path is data-driven: `putFigureSources`
(`CollegeMatchRow.kt:349-360`), `presentIn` / `inWireOrder` and
`FIGURE_SOURCES_NOTE` need **zero** edits for four more figures. Per figure: a
wire key, a cohort constant (one per dictionary CELL — folding two cells into
one constant is forbidden at `ScorecardOutcomeCitations.kt:70-76`), a `Citation`
entry, a nullable `CollegeMatch` field, and one `putOrNull`. Exactly two `when`s
over `Citation` exist and the compiler forces both.

The sentences a family hears, as landed. The population clause in each is the
publisher's own: `NAME OF DATA ELEMENT` for the four variables, plus — because
the `NOTES` cell is EMPTY for all three `OMAWDP8_*` — the publisher's own four
sibling elements in the same `completion` category, `OMAWDP8_FTFT` / `_PTFT` /
`_FTNFT` / `_PTNFT` ("full-time, first-time" … "part-time, not first-time"), of
which `_ALL` is the union.

- `completion_rate_8yr_share` — "the share of ALL students who entered that
  school in the 2016-17 academic year -- full-time and part-time, new to college
  and transferring in -- who had been awarded a degree or certificate within
  eight years, as the U.S. Department of Education's College Scorecard reports
  it".
- `completion_rate_8yr_pell_share` — "the share of the students who entered that
  school in the 2016-17 academic year and received a federal Pell Grant who had
  been awarded a degree or certificate within eight years, as … reports it -- a
  group the publisher counted, not a forecast for any one student".
- `completion_rate_8yr_non_pell_share` — the same, "and did not receive a
  federal Pell Grant".
- `transfer_out_rate_4yr_share` — "the share of students who started full-time,
  for the first time, in autumn 2018 and transferred to another school instead
  of finishing at that one, as … reports it -- counted out of the same six-year
  group as the six-year completion rate, and leaving is not the same as not
  finishing".

Two things those sentences deliberately do NOT do. **Neither Pell arm leans on
"the same figure as above"** — they are separate dictionary cells, so a snapshot
may re-date one alone and the word "same" would then be a lie no test could see;
each restates its own period in full. And **no sentence compares the two arms**,
which is asserted: the measured 65.8% makes any comparison wrong about one
school in three, and a relation between two figures is not a per-figure
sentence's to carry.

### 5. The coach's own words — three sentences, one paragraph, one pin

Sayability needs no prompt edit: the outcome-figures paragraph is quantified
over "each figure whose `figure_sources` entry is present", never over a list of
three. Three sentences ARE forced, because each is a relation between figures
that no single citation can carry:

1. two completion figures over different students and windows are two measures,
   never one measure improving (the acceptance criterion, and D9);
2. the Pell and non-Pell figures are two groups the publisher counted, never a
   prediction about this student, and the coach never decides which is theirs
   (D48; and the measured 65.8% is why the coach states both and narrates no
   gap);
3. a transfer-out share is a move, not a failure, and never "dropping out".

They go INSIDE the existing paragraph (after its last line, before the next
paragraph's opener), so no `CoachParagraph` opener moves and the five asserted
literals stay in the string the test reads. `bin/prompt-seed` writes the seed,
the `ORDER` line and the pin at land; live pin at design time was `v44`, and
this run minted `v46` (rollback `v45`). The label moved during the run: two
sibling runs landed while this one was open, one of them minting the `v45` this
run had generated, so `bin/prompt-seed` recomputed the label against the rebased
base — which is what makes a hand-typed pin wrong and this one right.

### 6. The fit lens carries it too, because the compiler says so

`FitLensService.digestFieldOf` is an exhaustive `when` over `Citation`, so the
code does not compile until the digest names the four figures — and
`appendOutcomeFigureNote` is driven by `presentIn`, so it prints four more stamp
sentences automatically. The row line must therefore also print the four values,
or the digest hands the model sentences about fields no row shows.
`appendOutcomeFigureNote` is currently untested; this run lands that test on the
existing `call2Text` harness.

### 7. What this does NOT reach, stated rather than claimed

After this run, a family who NAMES one school and asks whether students finish
there still has no tool that answers: search has no name filter,
`similar_colleges` never returns the anchor row, `find_college` returns four
keys, and the cost and admissions tools are scoped to the student's own list.
**That gap exists today for the six-year figure and is not made worse here.** It
is returned to /chart as a candidate slice, not closed in this run.

Nor does the coach's new leading figure reach the FILTER or the SORT. A search
narrowed by `min_completion_rate_150pct_4yr_share`, or ordered by it, is still
chosen and ranked on `C150_4` (`CollegesDao.kt`), because those two read
`college_search_index` and this run deliberately adds no index column for the
four: they are returned context, nothing filters or sorts on them, and the
payload reads them off `colleges` for the at-most-25 rows it returns (D60). So
the coach can lead with the eight-year figure over a result set that a six-year
figure chose, and the 1,290 schools that gained an eight-year figure but report
no `C150_4` are still counted into `excluded_unknown` on a completion-filtered
search. That is acceptable for now for two reasons: nothing in the payload or
the copy claims the list was chosen on the figure being spoken, and the
alternative is four new index columns plus a rebuild, which is a larger change
than this slice was approved for. It is returned to /chart beside the gap above,
not closed here.

## Files Modified

| File                                                                | Edit                                                                                                                                                                                                                                                     |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/add-eight-year-completion-and-transfer-out.sql`          | NEW — four columns on `colleges` + `colleges_versions`, `CREATE OR REPLACE log_college_version()`                                                                                                                                                        |
| `db/seed/scorecard/dictionary-variable-sources.csv`                 | four rows, transcribed from the publisher's workbook                                                                                                                                                                                                     |
| `db/seed/scorecard/FETCH-NOTES.md`                                  | the new download's URL/date/bytes/sha256, and the transcription's new digest                                                                                                                                                                             |
| `college/.../ScorecardInstitutionColumns.kt`                        | four `const val`; `OUTCOME_VARIABLES` 3 -> 7                                                                                                                                                                                                             |
| `college/.../CollegeScorecardLoader.kt`                             | four `COL_*` aliases, four required-column entries, four reads; `METHOD_VERSION` 10 -> 11, because the four columns join the non-null census and `change_summary`'s `non_null` axis goes 16 keys -> 20                                                   |
| `college/.../ScorecardOutcomeCitations.kt`                          | four cohort constants, four `Citation` entries, KDoc                                                                                                                                                                                                     |
| `college/src/test/.../ScorecardDictionaryTranscription.kt`          | NEW `Cohort.StatedEnteringAcademicYear` arm + its `AY <label> cohort` reader (a TEST-source file; see §2 above)                                                                                                                                          |
| `college/.../CollegeMatchRow.kt`                                    | four wire keys, four figures written through their own `Citation`, and the wire-key KDoc's count. `FIGURE_SOURCES_NOTE` is UNCHANGED: §4 — it is quantified over the figures `figure_sources` lists, not a list of three, so it is already true of seven |
| `db/.../College.kt`, `CollegeMatch.kt`, `NewCollege`, `CollegesDao` | four nullable fields on all three models, mapper and insert/update columns, and four `CollegesDao.NON_NULL_COUNTABLE_COLUMNS` entries (the census axis 16 -> 20; see §1)                                                                                 |
| `db/.../models/AssuranceTier.kt`                                    | four `SCORECARD_TIERS` entries                                                                                                                                                                                                                           |
| `service/.../FitLensService.kt`                                     | four `when` arms, four row fields                                                                                                                                                                                                                        |
| `prompts/coach-system-prompt.md`                                    | four sentences inside the outcome-figures paragraph: three relations, plus the gate's own ordering decision                                                                                                                                              |
| `service/src/main/resources/service.conf`                           | the rollback comment block (the pin line is written by `bin/prompt-seed`)                                                                                                                                                                                |
| tests                                                               | see below                                                                                                                                                                                                                                                |

## Implementation Plan

1. **Settle the period first.** Download the publisher's data dictionary once,
   record URL/date/bytes/sha256, transcribe the four `SOURCE` cells and the four
   leading cohort clauses. If a cell's shape is one the reader refuses, widen
   the reader and add a spoken form for an academic-year cohort in this step —
   not after the code is written. If that cannot be done honestly, drop
   `TRANS_4` first and never the Pell arms.
2. Migration + `colleges_versions` + the trigger function; `bin/db-migrate`.
3. Ingest: columns, required-column assertions, reads. Run the loader against
   the pinned snapshot and check the non-null census moves by the measured
   amounts (3,542 / 1,290 gained / 11 lost).
4. Citations, wire keys, `CollegeMatch` fields, `AssuranceTier` entries,
   digests.
5. Fit-lens digest arms and row fields, plus the missing
   `appendOutcomeFigureNote` test.
6. The three prompt sentences; `bin/prompt-seed -b "$BASE_SHA"`.
7. Suite.

## Tests

- **Exact-set assertions that must move, by name:**
  `CollegeSearchToolTest.kt:369-377` and `SimilarCollegesToolTest.kt:569,623`
  (`figureSources.keys` is exactly three today); the three method-version pins,
  `CdsSeedLoaderTest.kt:733`, `CollegeScorecardIngestTest.kt:528` and
  `IpedsIngestTest.kt:442`, which all read `METHOD_VERSION` as a literal (10 ->
  11), plus `CollegeScorecardIngestTest`'s `non_null` key-count assertion (16 ->
  20); `ScorecardOutcomeCitationsTest` (9 tests, including the closed-`when`
  guard); `ScorecardDictionaryPinTest` (CSV keys equal `DATED_VARIABLES`);
  `AssuranceTierTest` 27 -> 31 and 25 -> 29; `AssuranceTierClosureTest` (the
  literal three-outcome set and the transcription digest);
  `SystemPromptCatalogTest.kt:2440-2464` (its name and KDoc say "three stamped
  figures" and go stale).
- **New:** one citation test per new figure (period equals the committed
  dictionary cell; exactly one four-digit run per sentence; the publisher
  named); loader tests for the four columns including the `NA` arm; a refusal
  test per new required column — the derived institution header minus one of
  `OMAWDP8_ALL` / `OMAWDP8_PELL_ALL` / `OMAWDP8_NOPELL_ALL` / `TRANS_4` is
  fatal, names the missing variable, and writes nothing (RFC 149's triggered
  shape, not a membership assertion); a search-payload test that all seven
  stamps appear and that a figure absent from every row is not stamped; the
  first `appendOutcomeFigureNote` test.
- **Copy scans that must stay green:** the standing prompt copy rules
  (`SystemPromptCatalogTest.kt:653-670`) and `BareSourceCodeGuard`.
- Suite: `nix develop -c bin/test`, full, no module scope.

## Gate — APPROVED by Ian, 2026-09-25, verbatim: "approve"

Two decisions, both taken at the recommended default:

1. **The door is the search payload.** The four figures ride `CollegeMatchRow`,
   so `search_colleges` and `similar_colleges` both carry them, and the fit-lens
   digest carries them because the compiler forces it. The money tool is NOT
   widened: it would overrun the 1,992-byte per-college provenance cap by about
   1,083 bytes, need a tool-description rewrite and a routing sentence, and
   still answer only for colleges already on the student's list.
2. **The coach leads with the eight-year figure.** The six-year figure is said
   only when the family asks for the published graduation rate, or when the
   eight-year figure is absent. Both keep riding the payload; no key is removed.

Recorded with the gate: the named-school gap (§7) is returned to /chart, not
closed here; the four new columns were presented as DDL; and the one deliberate
download of the publisher's data dictionary was presented and accepted on RFC
226's precedent.
