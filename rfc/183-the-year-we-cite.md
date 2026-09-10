# RFC 183 — The year we cite

Slice: `soft/03/the-year-we-cite` (brief 0008, gate 2 approved 2026-09-05). Lane
A. Approved by Ian 2026-09-09, all defaults (D1 option B, D2-D5).

## Summary

Every College Scorecard figure unicoach serves carries an academic year that is
wrong by two years. `CanonicalMoneyLoader` stamps the Scorecard's published
charges `AcademicYear(2022)` when the publisher's own dictionary says they
describe AY2024-25, and stamps `COSTT4_A` and the whole `NPT4` family
`AcademicYear(2021)` when the dictionary says AY2023-24. A derived citation is
only as honest as the year it states, so brief 0008 owns this defect (gate-1
D6): the hedge work cannot say where a number came from while the year beside it
is a fabrication.

The correction is two constants. What makes it an RFC rather than a typo fix is
what the honest years then do to precedence — and one thing they would do is
ship a wrong number to families at 246 colleges. This RFC corrects the years
**and** refuses that number.

```
PUBLISHED_PRICE_YEAR      AcademicYear(2022) -> AcademicYear(2024)
BLENDED_AVERAGE_VINTAGE   AcademicYear(2021) -> AcademicYear(2023)
```

Both are off by two, not one. `AcademicYear`'s int is the START year
(`AcademicYear.kt:41,:67-68`), so 2024 is the label "2024-25".

## What the publisher actually says

Measured against the pinned snapshot, not against memory. The repo pins
Scorecard release `06102026` (`db/seed/scorecard/PROVENANCE.json`);
`CollegeScorecardDataDictionary.xlsx` for that release is sha256
`2f314d1d…fa1740`, and its `Most_Recent_Inst_Cohort_Map` sheet dates every
variable we load:

| Variables                                                                                             | Dictionary says  | We stamp today    |
| ----------------------------------------------------------------------------------------------------- | ---------------- | ----------------- |
| `TUITIONFEE_IN/OUT`, `ROOMBOARD_ON/OFF`, `BOOKSUPPLY`, `OTHEREXPENSE_ON/OFF/FAM`                      | AcadYr 2024-25   | 2022-23           |
| `COSTT4_A`, `NPT4_PUB/_PRIV`, `NPT41…45` both suffixes                                                | AcadYr 2023-24   | 2021-22           |
| `GRAD_DEBT_MDN`, `DEBT_MDN` (NSLDS pooled FY2020/FY2021), `MD_EARN_WNE_P10` (Treasury pooled cohorts) | no academic year | undated — CORRECT |

The two administrative-record measures carry no academic year, and
`VINTAGE_UNDATED` is already right for them. Nothing about them changes.

## Decisions

**D1. The year fix alone would ship a wrong number. We refuse it at the write
seam.** _(Approved: option B.)_

Once the Scorecard's charges are honestly dated 2024-25 they become the newest
complete published price, and the service serves the latest year that completely
prices a living arrangement (`CollegeFigures.publishedPriceYearOf`, `:912-918`).
So families move off IPEDS's **three** residency tiers onto the Scorecard's
**two**. The Scorecard has no in-district cell at all; for a district-based
college its `TUITIONFEE_IN` carries the in-district amount while its dictionary
calls the field "In-state tuition and fees" (`Institution_Data_Dictionary` row
623, SOURCE "IPEDS", NOTES empty). The collapse is undocumented and observable
only by measurement.

Measured over the pinned artifacts: 3,299 colleges would serve that cell as
in-state. **3,035 are safe** — their IPEDS in-district equals their in-state, so
nothing collapses. **264 collapse, and 246 of those understate the true in-state
price**: median **$1,800**, p90 **$5,400**, max **$16,778** (147244 Millikin
University, serving 26,892 against a true in-state 43,670). The slice's own
worked example is one of them: Austin Community College (222992) would serve
**2,550** as in-state when in-state is **8,580** — a **$6,030** understatement
to any Texas student living outside the district.

That is precisely the in-district/in-state collapse RFC 161 landed to remove,
and telling a family a price $6,030 below what they would actually pay is the
kind of claim brief 0008 exists to end. A year fix that trades a wrong **label**
for a wrong **number** is not a fix.

So: when a college's IPEDS charge rows carry **both** an in-district and an
in-state tuition figure **and the two differ**, the Scorecard's `TUITIONFEE_IN`
cell is **not written**. Everything else the Scorecard publishes for that
college is written as normal — housing and food, books and supplies, other
expenses, out-of-state tuition, and every net-price row.

**The evidence must be explicit, and absent evidence withholds.** The rule's
evidence is IPEDS's own residency tiers, and the IPEDS group is OPTIONAL at the
ingest CLI — a Scorecard-only fill is supported (`fill()`'s KDoc, and
`CanonicalMoneyLoaderTest` calls it). A rule that inferred its evidence from
whatever happened to be in the shared accumulator would therefore fall silently
OPEN in exactly that run: the set comes back empty, every collapsing college's
in-district amount is written as in-state at 2024-25 — the newest year, hence
the served one — and the understatement D1 exists to prevent ships with nothing
in the log. So the IPEDS arm of the fill TELLS the Scorecard arm whether it
staged residency tiers, and with no evidence the in-state cell is withheld for
**every** college. A withheld cell degrades to whatever IPEDS already stored,
labelled; a wrongly-labelled cell reaches a family. This brief refuses the
second.

**And the refusal is counted, because every other non-write in this fill is.**
`ScorecardLosses` already tallies `rowsMalformed`, `rowsWithoutCollege`,
`rowsWithoutControl` and `ipedsChargesIgnored`, and the file states the rule in
its own words: never a silent `continue`. Two counters, mutually exclusive by
construction, reach `FillResult` and the operator summary —
`inStateTuitionWithheld` (this college's tiers really differ),
`inStateTuitionUnevidenced` (this run staged no comparable tier pair at all) and
`inStateTuitionWrittenUnmeasured` (IPEDS filed no usable pair for this college,
so the cell was written on no evidence). Each refusal also logs ONE debug line
carrying the evidence that decided it — the college, the year, both amounts, and
for an unmeasured college the publisher's own `AbsenceStatus` — because a count
says how many and only that line says which and why. A predicate that suppressed
all 6,273 colleges must not look identical to one that suppressed 264, and the
unevidenced case warns with its remedy: supply the IC_AY input and re-run.

Three properties make this the narrow choice:

- **It suppresses; it never manufactures.** We do not invent an in-state figure
  for the Scorecard, and we do not compare amounts across publishers or years to
  guess one (`rfc/170:60-66`). The test is structural — does this college have a
  residency distinction the Scorecard cannot express? — not numeric.
- **The existing read rule does the rest, unchanged.** With no 2024-25 in-state
  tuition row, that year no longer completely prices an in-state family's
  arrangement, so `publishedPriceYearOf` falls that family back to the coherent
  IPEDS 2023-24 budget on its own. No read-side edit, and
  `CostBreakdown.kt:490-499`'s one-year-per-served-answer rule is untouched —
  the candidate sets already depend on which tuition field applies to the
  family. An in-district or out-of-state family at the same college still gets
  2024-25.
- **It protects every reader, not one.** Refusing at write keeps the mislabelled
  cell out of `price_figures` entirely, so chat, the parent-facing Family Cost
  Report and price-ranked search (RFC 169) are all covered by one rule. A
  read-side preference would have protected only the cost-breakdown path.

**D2. RFC 161's displacement stops firing, and this RFC says so plainly.**
_(Approved.)_

The slice text predicted the opposite, and the slice text is wrong. IPEDS
`IC_AY` stages four years, AY2020-21 through AY2023-24
(`IpedsChargeVocabulary.kt:36,39,113-116`), so today's false 2022 stamp lands
**inside** that window: the Scorecard's price cells collide with IPEDS on
**29,704** rows and lose, which `CanonicalMoneyIpedsTest.kt:100-118` already
asserts green with a comment saying as much. At the honest 2024 the Scorecard
sits **past** the IPEDS window and the displacement fires **0** times.

That is not a regression. What fired today was IPEDS AY2022-23 suppressing a
Scorecard AY2024-25 figure under a 2022-23 label — a newer number hidden by an
older one because the older one was mislabelled as newer. Once both publishers
are honestly dated they are simply one year apart and never contend, and source
precedence on price cells becomes dead code by construction, because `PriceKey`
includes the academic year (`CanonicalMoneyLoader.kt:1040-1046`).

The `NPT4` half of the defect is the real collision and it does go away: IPEDS
SFA writes 2020/2021/2022 (`CollegeSfaLoader.kt:264-265`), so today's false 2021
stamp collides and a 2021-22 net price displaces a 2023-24 one under a 2021-22
label. At 2023 it does not. **19,780 income-band net-price rows that are
suppressed today start being served.**

**D3. `PCTPELL` is a second, smaller vintage defect and is NOT fixed here.**
_(Approved.)_ The publisher dates it AcadYr 2023-24; the loader stamps
`VINTAGE_UNDATED` (`CanonicalMoneyLoader.kt:1010`). It is a share rather than a
price, and moving a currently-undated key onto a dated one has collision
consequences nobody has measured. Reported to /chart as a follow-up rather than
smuggled into this run.

**D4. No DDL.** No table, no column, no migration, no `db/schema/ORDER` line.
Brief 0008 gate-2 G6 holds.

**D5. No coach prompt CHANGE — but this run does land a regenerated seed.**
_(Approved as "no new prompt version"; amended here to state what RFC 181's land
contract does, because the code wins over the RFC.)_

No seeded prompt body contains an academic year — the immutable `system_prompts`
rows say "Say the academic year the tool gives" — so nothing about the coach's
words changes here. What did change is the mechanism: RFC 181 landed while this
run was in review, and it makes `db/schema/seed-coach-system-prompt-v<N>.sql` a
**generated artifact**, regenerated by `bin/prompt-seed` in every run's land
sequence between the final rebase and the squash, with the label taken from the
catalog tip in the base being landed on (RFC 181 D2/D3). `ship-land` refuses a
run that skipped it.

So this run writes `seed-coach-system-prompt-v26.sql` and moves `service.conf`'s
`systemPromptVersion` pin to match. **The body is byte-identical to v25's**:
this is the label following the catalog tip, not an edit to what the coach says.
Rollback is unchanged — `COACHING_SYSTEM_PROMPT_VERSION=v25`.

## RFC 161's agreement claim, restated honestly

RFC 161 recorded 92.1% agreement between Scorecard and IPEDS tuition. Measured
now over the pinned artifacts, on the same `CHG1AY3`/`CHG2AY3` pair RFC 161 used
(`rfc/161:14-20`): N=3,325 joinable colleges, **20.3% exact agreement** (2,649
mismatches, 2,204 of them Scorecard-higher), 28.2% within 1%, 75.6% within 5%,
median absolute difference $394, p90 $2,352, **median ratio 1.0262**.

The cause is not a dispute between publishers. IPEDS's own year-over-year ratio
across the same file is **1.0265** (N=3,404; 1.0271 restricted to the 3,325-pair
join). The two figures differ by one year of inflation, which is exactly what a
one-year vintage gap looks like. Per `rfc/INVARIANTS.md`, RFC 161's file is left
as committed; this paragraph is the correction.

## What changes for families, measured

Simulated over the pinned artifacts by replaying the loader's key and precedence
logic, validated against the repo's committed tests. The dev database holds zero
`price_figures` rows, so a population count from a live DB was not available;
every number below is reproducible from the commands in
`.scratch/ship/rfc-183/research/impact.md`.

Stored rows, after a re-ingest:

| Table                                   | Today   | After   | Why                                                                                  |
| --------------------------------------- | ------- | ------- | ------------------------------------------------------------------------------------ |
| `price_figures`                         | 198,704 | 228,144 | 20,480 Scorecard rows re-dated; 29,704 newly appear; 264 in-state cells refused (D1) |
| `cohort_money_stats` (Scorecard, dated) | 24,131  | 43,911  | 19,780 band rows no longer suppressed by the false SFA collision                     |

Served figures — one direction, IPEDS to Scorecard, never back:

- served **in-state** tuition changes at **2,368** colleges, all of them the
  harmless case (IPEDS in-district equals in-state; median change **+$517**, one
  year of inflation). The 262 harmful changes are refused by D1 and do not
  happen.
- served **out-of-state** tuition changes at 2,636 colleges, median **+$695**.
  The Scorecard's out-of-state cell carries no residency ambiguity.
- **net-price band** values change at 19,534 cells across 4,887 colleges, median
  **+$240**, as the served source flips from SFA 2021-22 to Scorecard 2023-24.
- **0** colleges lose their served in-district tuition. Without D1 it would be
  64.

Austin Community College (222992), the slice's named example: in-district 2,550
unchanged; in-state stays **8,580** (IPEDS AY2023-24) rather than collapsing to
2,550; out-of-state 10,590 unchanged in value, source flips to the Scorecard;
net-price bands rise $930-$3,981 (under-30k: 1,278 -> 3,789).

## The pin: the year is asserted against the publisher, not re-typed

The acceptance criterion is that the stamped year equals the year the
publisher's own dictionary states, "pinned by test against the committed
dictionary — not a literal re-typed in Kotlin". The repo has no machine-readable
year for these variables today:
`db/seed/scorecard/dictionary-variable-sources.csv` is a 24-row, two-column
transcription (`variable_name,source`) and `PROVENANCE.json` records only
`release: "06102026"`, no survey year.

So the same committed artifact gains one column, `most_recent_cohort`, holding
the dictionary's own string verbatim ("AcadYr 2024-25", "AcadYr 2023-24", or
empty for the undated administrative measures). The test walks it with the
machinery RFC 179 already landed (`AssuranceTierClosureTest.kt:34-37,104-135`),
and the file's sha256 is pinned in two places that must move together (the test
constant at `:159` and `db/seed/scorecard/FETCH-NOTES.md:116-117`).

This is a transcription, and the RFC says so rather than pretending otherwise:
the JVM has no xlsx reader on the classpath and adding one to read a 727KB
spreadsheet at test time would be a heavier claim than the one being made. What
the pin buys is that the Kotlin constant can no longer drift from the CSV
silently, and that a snapshot bump that changes a cohort year fails a test
instead of quietly re-dating every price we serve.

## Detailed Design

### 1. The two constants

`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1149,:1152`.
`PUBLISHED_PRICE_YEAR` becomes `AcademicYear(2024)`, `BLENDED_AVERAGE_VINTAGE`
becomes `AcademicYear(2023)`. Both are read at exactly three sites — `:894`
(charges), `:977` (`COSTT4_A`), `:994` (`NPT4`) — and nowhere else in main.

Each constant's KDoc gains the dictionary sentence it now matches, and the
existing doc comments that assert the old, inverted rationale are **reworded,
not swapped**: `CanonicalMoneyLoader.kt:1143-1147`,
`CollegeCostService.kt:436-437,:447-448,:774`,
`CollegeCostChatTool.kt:1000,:1289`, `ComparisonBasis.kt:1142-1143`,
`CostField.kt:120`. A comment that says "IC_AY also carries this year, so IPEDS
wins" is not made true by editing a digit; it has to state the new fact, which
is that the publishers no longer contend.

### 2. Refusing the collapsed in-state cell

The Scorecard fill runs after the IPEDS fills in `ORDERED_SOURCES` and shares
the same `prices` map, so the IPEDS rows are already present and are the
evidence:

- a college **collapses** when, at the **newest academic year that files both
  tiers**, IPEDS filed a `TUITION_AND_FEES` amount at
  `ResidencyBasis.IN_DISTRICT` and one at `ResidencyBasis.IN_STATE`, **both
  actually present** (a `FigureReading.Present` amount), and the two differ. An
  absence carries no amount, and two absences are not a difference, so only a
  figure the publisher really filed can evidence a distinction. The newest year
  is the deciding one because a college that merged its two rates last year is
  not collapsed today, and ORing the test across every staged year would cost it
  its 2024-25 price forever;
- for such a college the `TUITION_AND_FEES` / `IN_STATE` write is skipped, and
  only that one. `TUITIONFEE_OUT` and every non-tuition cell are written.

The answer is carried by `ScorecardResidencyEvidence`: a private type with a
private constructor and one factory, built **once per fill** in the fill loop's
`SCORECARD` arm — the level that already owns precedence — from the collapsing
set plus the IPEDS arm's explicit "I staged residency tiers" flag. It answers
`inStateTuitionFor(collegeId)` with a named outcome, and the outcomes are a
sealed set of FOUR because four different things can be true:

| Outcome               | Meaning                                                   | Cell    |
| --------------------- | --------------------------------------------------------- | ------- |
| `Write`               | IPEDS measured this college and its tiers agree           | written |
| `WithheldTiersDiffer` | this college charges a district rate below its state rate | refused |
| `WithheldNoEvidence`  | this RUN staged no comparable tier pair at all            | refused |
| `WrittenUnmeasured`   | IPEDS filed no usable tier pair for THIS college          | written |

That the states are named is the point, and the fourth is the one a review
found: without it, "IPEDS measured this college and its tiers agree" and "IPEDS
filed nothing usable about this college" were the same answer. The run-level
unknown failed closed while the per-college unknown failed open and uncounted,
so an evidence collapse across half the corpus would have read as a healthy run.

`WrittenUnmeasured` **writes** the cell. Withholding for unmeasured colleges
would strip the in-state price from every college IPEDS does not cover — often
the ones where the Scorecard is all we have — in exchange for a risk nobody has
measured. The counter is what makes it measurable on the next ingest, and the
open question is recorded rather than answered by guess.

The run-level gate is correspondingly exact: it requires BOTH tuition tiers
staged with an actual `FigureReading.Present` amount. Testing the keys, or
accepting either tier alone, would let a fill whose rows exist but say nothing —
or which staged one side of a comparison — report the rule as satisfied.

A bare `Set<UUID>` would not have been enough. The fill-loop call site has
another `MutableSet<UUID>` in scope and `emptySet()` also compiles, so the
guarantee would have been held by spelling an argument correctly. The set now
never crosses a signature at all: membership is resolved once per row and passes
down as a named boolean. Per-college evaluation would have rescanned the shared
map 6,273 times for an answer whose evidence is complete before the Scorecard
fill begins.

Where the two IPEDS tiers are equal, or where either is missing, nothing is
skipped — an absent distinction is not a distinction, and refusing there would
withhold a newer figure for no reason (`rfc/166:507-509`, anti-vacuity: a rule
whose arms all return the same answer is not a rule).

### 3. The dictionary column and its test

`db/seed/scorecard/dictionary-variable-sources.csv` gains `most_recent_cohort`.
The existing sha256 pins move with it, in both places. A new test asserts the
two Kotlin constants against the CSV: every variable the charge fill loads reads
"AcadYr 2024-25" and equals `PUBLISHED_PRICE_YEAR`'s label; every variable the
blended fill loads reads "AcadYr 2023-24" and equals `BLENDED_AVERAGE_VINTAGE`'s
label; the two administrative-record measures (`GRAD_DEBT_MDN`/`DEBT_MDN`,
`MD_EARN_WNE_P10`) read empty and are `VINTAGE_UNDATED`.

**The group must DECIDE the year, not merely describe it.** A registry the fill
does not consult is a comment: a ninth charge column could join the charge group
and still be stamped the blended year at its call site, with the whole pin suite
green. So `ScorecardInstitutionColumns` declares ONE total map from column to
vintage group; the three group sets and `LOADED_VARIABLES` are **derived** from
it (contents byte-identical, so RFC 179's 24/22/2 assurance assertions are
untouched); and both write seams ask it for the year rather than naming a
constant. After this change `PUBLISHED_PRICE_YEAR`, `BLENDED_AVERAGE_VINTAGE`
and `VINTAGE_UNDATED` appear at **no write site at all** — the `vintage`
argument leaves `stat()` and its eight call sites — so the column's group is the
only thing that can date it. `UnregisteredColumnException` is reworded to match:
it now tells a fixer to give the column a vintage group, not to append to a list
that is now derived.

`PCTPELL` is the one **declared divergence**: the publisher dates it "AcadYr
2023-24" and the fill stamps it `VINTAGE_UNDATED`. The transcription states what
the dictionary says, because a transcription that edits its source is not one,
and a named test pins the gap so that fixing D3 must delete the declaration
rather than discover it.

## Files Modified

| File                                                                                                         | Change                                                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`                                        | two constants; the collapse predicate, computed once per fill and threaded into `mapScorecardRow`/`mapScorecardPrices`; comment rewording                                              |
| `college/src/main/kotlin/ed/unicoach/college/ScorecardInstitutionColumns.kt`                                 | RFC 179's `LOADED_VARIABLES` split into the three groups the fill dates, and rebuilt as their union — contents unchanged                                                               |
| `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt`                                           | the two new counters in the operator summary line, beside the ones already there                                                                                                       |
| `db/seed/scorecard/dictionary-variable-sources.csv`                                                          | new `most_recent_cohort` column, transcribed verbatim from the dictionary                                                                                                              |
| `db/seed/scorecard/FETCH-NOTES.md`                                                                           | sha256 update + one line on the new column and where it comes from                                                                                                                     |
| `college/src/test/.../AssuranceTierClosureTest.kt`                                                           | pinned sha256 constant                                                                                                                                                                 |
| `college/src/test/.../CanonicalMoneyVintageTest.kt` (new)                                                    | the dictionary pin and the collapse-refusal tests                                                                                                                                      |
| `college/src/test/.../CanonicalMoneyLoaderTest.kt`                                                           | expectations at `:151-154`, `:159-167`                                                                                                                                                 |
| `college/src/test/.../CanonicalMoneyIpedsTest.kt`                                                            | `:106-118`, `:169-175` — behaviour genuinely moves. `:240-253` is deliberately UNCHANGED: a fifth `2024 to 2550` entry would be the defect D1 refuses, so it gains a comment saying so |
| `college/src/test/.../CanonicalMoneySfaTest.kt`                                                              | `:83-105`; and `:51-66` re-pointed — see "A test that lost its subject" below                                                                                                          |
| `db/src/testFixtures/.../CanonicalCohortFixture.kt`                                                          | KDoc only: its comment claimed the fixture year IS `BLENDED_AVERAGE_VINTAGE`, which is now false                                                                                       |
| `service/src/main/.../CollegeCostService.kt`, `CollegeCostChatTool.kt`, `ComparisonBasis.kt`, `CostField.kt` | comment rewording only, no behaviour                                                                                                                                                   |

No `db/schema/**` change, no migration, no `ORDER` line, no prompt seed.

## Implementation Plan

1. Add the `most_recent_cohort` column to the seed CSV, transcribed from the
   pinned dictionary, and split `ScorecardInstitutionColumns`' registry into the
   three groups the fill dates; update both sha256 copies. Land the pin test
   FIRST, asserting the CURRENT constants — it must fail, proving it reads the
   artifact.
2. Change the two constants; the pin test goes green.
3. Reword the nine inverted doc comments.
4. Add the collapse predicate and skip the one cell. Add its tests.
5. Update the five affected test files, each with a comment saying which
   behaviour moved and why.
6. Full suite. Baseline is 3035 tests, 0 failures — report the executed counts.

## Tests

- **The dictionary pin.** Both constants asserted against the committed CSV, per
  variable. Fails if a snapshot bump re-dates a variable.
- **The collapse refusal, positive — stored AND served.** A college with IPEDS
  in-district 2,550 / in-state 8,580 plus a Scorecard `TUITIONFEE_IN` of 2,550
  writes no Scorecard in-state row (the fill), **and** an in-state family at
  that college is served 8,580 at the IPEDS year (the read). The second half is
  a separate assertion against `publishedPriceYearOf`, because withholding the
  cell only helps if the year chooser then falls back: its second arm can still
  return 2024 on the Scorecard's housing and books cells, and a stored-row test
  alone would never see that. Austin CC's real numbers, named in both.
- **The collapse refusal, negative (anti-vacuity).** A college whose IPEDS
  in-district equals its in-state DOES take the Scorecard's newer in-state
  figure. Without this the rule could suppress everything and still pass.
- **Neighbours unaffected.** At a collapsing college, out-of-state tuition,
  housing and food, and the net-price bands all still come from the Scorecard at
  the corrected years.
- **The NPT4 collision is gone.** The SFA 2021-22 row and the Scorecard 2023-24
  row no longer share a key; the band figures are served.
- **The re-dated rows.** Distinct academic years and vintages are 2024 / 2023.
- **The year a family is told.** A served Scorecard tuition figure is spoken as
  "2024-25" **in a real sentence** — asserted through the copy path
  (`ComparisonBasis.kt:1191-1202`, reused verbatim on
  `CostReportPage.kt:319,:356`), not merely `AcademicYear(2024).label`. A label
  assertion passes with every copy site deleted, which is the vacuity RFC 166
  names.

- **Fail closed, and counted.** A fill with no staged IPEDS residency evidence
  writes no in-state Scorecard cell for any college and reports the unevidenced
  count apart from the ordinary one. Proved by reverting the rule: the test
  fails with `expected: <5> but was: <0>`.
- **The newest year decides.** A college whose older year files differing tiers
  but whose newest year files equal ones is NOT collapsed and keeps its 2024-25
  price. Proved by reverting to the any-year rule.
- **The publishers cannot silently contend again.** `PUBLISHED_PRICE_YEAR` sits
  past `IpedsChargeVocabulary.SURVEY_YEAR`'s window, so bumping the survey year
  alone fails a test instead of quietly restoring the displacement.
- **Every registered column's group dates it.** The stamped year is asserted per
  column against its vintage group, so the group is load-bearing rather than
  decorative.

### A test that lost its subject

`CanonicalMoneySfaTest.kt:51-66` pinned a specific SFA/Scorecard key collision.
After this change **no two publishers can share a natural key anywhere in the
fill** — SFA writes 2020-2022, `IC_AY` 2020-2023, the Scorecard 2023 and 2024 —
which is D2 restated: source precedence on price cells is now dead code by
construction. There is no still-live collision to re-point it at, so leaving the
test asserting a key that cannot exist would be a green test of nothing.

It is re-pointed at the fact that replaced it: the two publishers' band series
**coexist**, one row per real year, never averaged and never displaced — all
four Portland State under-30k rows, including the Scorecard's 16,348 at 2023
that today's false stamp suppresses. That is the fixture-scale instance of the
19,780 rows this RFC frees. The consequence is recorded rather than hidden:
`ORDERED_SOURCES` precedence is now exercised only by its own structural tests,
never by live fixture data.

## Operational note

The constants are read at fill time only, so this takes effect on a database
after a **full re-ingest**; there is no standalone phase flag:

    nix develop -c bin/ingest-colleges -H <host> -Y ic2023_ay.csv -y 2023 \
      -S sfa2223.csv -f 2022 inst.csv fields.csv

No migration is involved. The ledger records the re-ingest as part of this
slice's close-out.

**Read the three new counters on that run.** `inStateTuitionWithheld` should be
in the low hundreds — 264 over the pinned artifacts. `inStateTuitionUnevidenced`
should be **zero**; a non-zero value means the run staged no comparable IPEDS
tier pair, so every college's in-state Scorecard cell was withheld — the loader
warns with that remedy, and the fix is to supply the IC_AY input and run again.
`inStateTuitionWrittenUnmeasured` is the population nobody has measured yet:
colleges IPEDS filed no usable tier pair for, whose in-state cell we write on no
evidence. If it is large, the next question this brief should ask is how many of
THOSE colleges are district-based.

## What this does not do

- It does not fix `PCTPELL`'s vintage (D3).
- It does not change how any figure is worded — brief 0008's copy work is RFC
  177 and RFC 179, already landed.
- It does not make IPEDS and the Scorecard contend again. They are one year
  apart and that is a fact about the publishers, not a policy.
- It does not add a residency tier to the Scorecard. Where the publisher
  collapses two prices into one we withhold the ambiguous cell; we never split
  it.

## Learning candidates

Recorded for `/principle`, not acted on here.

- A spec's stated CAUSE can be false while its stated DEFECT is real. This slice
  named the wrong mechanism (displacement "never fires"; it fires 29,704 times)
  and the right fix. A principle would have to say: verify the mechanism a spec
  asserts before building on it, and report the correction rather than quietly
  building the right thing.
- Correcting a vintage is a precedence change. Any edit to a stored year is
  really an edit to who wins a key, and the review question is "who serves this
  figure now", not "is the year right".
