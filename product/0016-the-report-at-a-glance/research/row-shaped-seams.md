# Row-shaped seams for the table-first Family Cost Report

Research report for brief 0016. Every claim carries a `path:line` into the root
checkout (`/Users/ian/Work/unicoach`, `main` at `2aad6f8b`). Paths are
repo-relative. Quoted strings are exact.

## METHOD

Read in full:
`public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` (2,403
lines; the block functions at :1424–2403 and the price-table section at
:736–1392),
`service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt`,
`.../admissions/MeritAidWire.kt`, `.../costs/ProgramReturnCopy.kt`,
`.../costs/canonical/AssuranceTierCopy.kt`, `AssuranceTierGrouping.kt`,
`MoneySourceCopy.kt`, `FigureStatusCopy.kt`, `.../admissions/CdsCitation.kt`,
`.../costs/BorrowingCoverage.kt`, `BorrowingAtGraduation.kt`,
`.../costs/ProgramReturn.kt`, the model files `db/.../models/AutomaticAward.kt`,
`NationalMeritAward.kt`, `CollegeBorrowing.kt`, `LoanType.kt`,
`PublishedCell.kt`, the relevant slices of `CollegeCostService.kt`,
`NationalMeritService.kt`, `AutomaticAwardService.kt`, `CollegeCostChatTool.kt`,
and `public-web/.../render/DataTable.kt`, `ExplainerPage.kt`, `FafsaPage.kt`,
`public-web/src/main/resources/static/site.css`.

Tests read: `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`
(2,924 lines; every test name listed, the borrowing / tier / grid / National
Merit / measurement tests read whole), `CostReportProgramReturnTest.kt` (whole),
`ReportProse.kt` (whole), `CostReportRoutingTest.kt` and
`report/ServiceCostReportSourceTest.kt` (test names),
`service/src/test/.../
SystemPromptCatalogTest.kt` (the figure-status,
answer-shape, borrowing and merit paragraphs), `AssuranceTierCopyTest.kt`,
`BorrowingWireTest.kt`, `ProgramReturnCopyTest.kt`,
`college/src/testFixtures/.../FigureComparisonWords.kt`.

Commands run (all read-only): `git worktree list`,
`git branch --list
'pipeline/*' 'ship/*' 'docs/*' 'quality/*'`,
`git rev-list --count` and `git diff --name-only main...<branch>` for every
listed branch and detached worktree, `git log --oneline main..pipeline/rfc-258`,
`git show
pipeline/rfc-258:service/src/main/kotlin/ed/unicoach/family/FamilyCostPictureChatTool.kt`,
and `grep -rn` sweeps for every seam symbol and every frozen sentence across
`service`, `rest-server`, `public-web`, `college`, `db`, `ios-app`, `prompts`
and `db/schema`. No file in the root checkout was edited; no Gradle or test was
run.

## Page layout today (where the blocks sit)

`schoolSection` (`CostReportPage.kt:852`) renders, per school, in this order:
`tuitionTierLine` (:881), `priceTable` (:882), `meritBlock(cost)` (:884),
`awardGridBlocks(cost)` (:885), `nationalMeritBlocks(cost)` (:886),
`debtBlock(cost, subjects)` (:887). Inside `debtBlock` (:1965) the order is the
federal-debt paragraph (:1973–1981), `programReturnBlocks(subjects)` (:1985),
`borrowingParagraphs(cost)` (:1986) — all three inside one
`section("report-debt")` (:1971). After the schools come `tierKindsSection`
(:2160) and `sourcesSection` (:2335). The order merit → grid → National Merit →
debt is asserted by
`the National Merit block follows the grid and precedes
debt`
(`CostReportPageTest.kt:2765`, asserts at :2786–2788) and
`the grid is a
sibling of the merit block and never inside it` (:2270).

The page writes exactly two kinds of table today: `summaryTable` (:745,
`table("$DATA_TABLE_CLASS report-table report-summary-table")`) and `priceTable`
(:943, `table("$DATA_TABLE_CLASS report-table")`). Every other block is `<p>`
and `<ul><li>`.

## meritBlock (`CostReportPage.kt:1424–1449`)

### Typed value

`CollegeCost.meritAid: MeritPractice?` (`CollegeCostService.kt:384`), read in
`CollegeCostService` from `CdsAdmissionsDao.listLatestMeritAid`
(`CollegeCostService.kt:1400–1404`) and built by
`MeritPractice.from(college.name, it)` (:1742). `MeritPractice`
(`CollegeAdmissionsService.kt:63`) carries `fullTimeFreshmen: Int?` (:65),
`nonNeedMeritRecipients: Int?` (:67), `averageNonNeedAid: Int?` (:69),
`source: CdsCitation` (:70), and the derived
`shareOfAllFullTimeFreshmen: Share?` (:81, null when either count is missing).
`Share` is a `value class` over `percent: Double` (`common/.../Share.kt:18–19`)
with `spoken()` (:32) and `spokenPercent()` (:40).

The block returns before opening a section when both `share` and `average` are
null (:1428).

### Copy seam and its consumers

The page prints `MeritAidWire.shareLabel(it)` (:1446) and
`MeritAidWire.averageLabel(it)` (:1447), each with a `.` appended by the page.
The sentences (`MeritAidWire.kt:75`, :84–86):

- `"${share.spoken()}% of all full-time freshmen received non-need (merit) aid"`
- `"the average non-need (merit) aid was ${WholeDollars.spoken(amount)} - a grant the student never pays back, reported for last year's class, not an offer to this student"`

Other consumers of the same seam: `MeritAidWire.objectOf` emits both numbers AND
both labels under `share_label` / `average_label` (`MeritAidWire.kt:52–71`) and
is called from `CollegeCostChatTool.kt:613` and
`CollegeAdmissionsChatTool.kt:114`; the tool descriptions point the coach at
`SHARE_LABEL_KEY` (`CollegeAdmissionsChatTool.kt:272–273`). The coach prompt
pins the vocabulary, not the sentence: `SystemPromptCatalogTest.kt:1216–1219`
asserts the served admissions paragraph contains `"of all full-time freshmen"`
and `"non-need (merit) aid"`. No REST payload and no iOS code reads these keys
(`grep -rl 'merit_aid|borrowing_at_graduation|program_return|cited_as' ios-app`
returns 0 files).

Citation: `p("report-source") { +"Source: "; sourceLink(merit.source); +"." }`
(:1440–1444); `sourceLink` (:2299) renders `CdsCitation.citedAs`
(`CdsCitation.kt:48`:
`"$collegeName's ${cycleLabel(sourceYear)} Common Data Set"`) as an anchor to
`linkTargetOrNull()` (:128) or as bare text when the file is recorded gone.

### Frozen vs shared

Nothing in this block is byte-frozen against a prompt row. The two label
sentences are shared code used by the coach payload; they are pinned by
`CollegeCostChatToolTest.kt`, `MeritAidWireLocaleTest.kt`,
`CollegeAdmissionsChatToolTest.kt` and `CostReportPageTest.kt` (grep of the
literals). The page-only strings are the heading
`"Money this school gives for something other than financial need"` (:1431) and
`"Source: "` (:1441).

### Page tests on this block

- `merit practice is cited to the school's own Common Data Set`
  (`CostReportPageTest.kt:471`): asserts
  `"of all full-time freshmen received non-need (merit) aid"` (:474) and
  `"Riverside State University's 2024-25 Common Data Set"` (:475).
- `a merit row with no measure under it renders no merit section at all` (:815).
- `each block links that school's own file, never our mirror` (:1957) and
  `the sentence that promises a link is never printed without one` (:1930): the
  latter asserts `assertEquals(6, countOf(body, "rel=\"noreferrer\""))` for
  three schools with a merit filing and a borrowing filing each (:1952).
- `no Common Data Set filing is cited anywhere on this page outside a link`
  (:1893): a regex `[^<>]*'s \d{4}-\d{2} Common Data Set` must match only inside
  `<a>` (:1898–1909). A table whose `<caption>` names the citation as TEXT would
  fail this unless the caption contains the anchor.
- `the report renders the money vocabulary and never the words it must not say`
  (:664): `"award"` may not appear anywhere in the lower-cased body (:673–676) —
  which is why the heading says "scholarships".

### Smallest row seam

`MeritPractice` already IS the structured value; no new type is needed. The
coach's `shareLabel`/`averageLabel` and the page's cells would both read
`merit.shareOfAllFullTimeFreshmen` and `merit.averageNonNeedAid`.

| fact in today's sentence                      | field                                                                                                                                                      |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `"12.3%"` share                               | `MeritPractice.shareOfAllFullTimeFreshmen: Share` (`.spokenPercent()`)                                                                                     |
| "of all full-time freshmen" (the denominator) | `fullTimeFreshmen: Int?` — currently NOT printed as a number on the page; only the wire carries `full_time_freshmen_headcount` (`MeritAidWire.kt:29, :55`) |
| recipients count                              | `nonNeedMeritRecipients: Int?` — not printed on the page today; wire key :30, :56                                                                          |
| "the average ... was $X"                      | `averageNonNeedAid: Int?`                                                                                                                                  |
| "a grant the student never pays back"         | hedge → caption/kinds section                                                                                                                              |
| "reported for last year's class"              | hedge → caption (the cycle is already in `citedAs`)                                                                                                        |
| "not an offer to this student"                | hedge → already said once in `sourcesSection` (:2359–2360 `"They are not an offer"`)                                                                       |
| citation                                      | `source: CdsCitation` → caption via `sourceLink`                                                                                                           |

A table here gains two reachable facts (the two headcounts) the page drops today
and the coach already has — consistent with brief 0009 D1.

## awardGridBlocks / awardGridBlock / bandLine (`CostReportPage.kt:1475–1726`)

### Typed value

`CollegeCost.automaticAwards: List<AutomaticAwardAnswer>`
(`CollegeCostService.kt:440`), read from
`automaticAwardService.gridAnswersInSession(session, selection.selected)`
(:1445–1448) — the student-free door. `AutomaticAwardAnswer`
(`AutomaticAwardService.kt:249`) has five arms: `NotCurated` (:253),
`NonePublished` (:258), `NoScoreEffect(award)` (:267), `Expired` (:273),
`ScoreBands(award, lastUsableSitting, boundBy, closedSittings, quotedCutOff)`
(:280). `printableGridOf` (`CostReportPage.kt:1503–1530`) prints only
`NoScoreEffect` and `ScoreBands`, as
`PrintableGrid(award, heading, scoreIsMoney)` (:1480–1485).

`AutomaticAward` (`db/.../AutomaticAward.kt:302`): `entryYear: AcademicYear`,
`residencyBasis: ResidencyBasis` (:308), `scoreRequirementAsPublished: String?`
(:312), `scoreDeadline: LocalDate?` (:317), `publisher: String` (:323),
`checkedOn: LocalDate` (:328), `bands: List<AutomaticAwardBand>` (:341).
`AutomaticAwardBand` (:221): `awardName: String` (:229), `score: BandScoreAxis`
(:234; `NoScore` :187 or `Range(test, low, high)` :190),
`gpaLow`/`gpaHigh:
BigDecimal?` (:236–237), `awardPerYearUsd: Int?` (:239),
`awardAsPublished:
String?` (:246), with
`require(awardPerYearUsd != null || awardAsPublished != null)` in `init`.

Every value is already structured. The only page-authored composition is in
`bandLine` (:1669–1683), which joins `awardName`, the asks and `paysOf` with
`" — "` into one `<li>` string (:1681–1682); `paysOf` (:1695–1700) formats
`"${WholeDollars.spoken(it)} a year"` or the school's words; `scoreRangeOf`
(:1703–1707) formats `"${axis.test.displayName} ${axis.low}–${axis.high}"`;
`gpaRangeOf` (:1716–1726) formats `"GPA $low–$high"` / `"GPA $low and above"` /
`"GPA up to $high"` through `PublishedGpa.of` (`common/.../PublishedGpa.kt:29`).

### Copy seam and consumers

There is NO shared sentence seam for this block: every string is private to
`CostReportPage.kt`. The coach's `AutomaticAwardChatTool` emits the band as JSON
fields (`gpa_low`/`gpa_high` through `PublishedGpa.of` at
`AutomaticAwardChatTool.kt:321–322`; `publisher` :210, `checked_on` :213). The
one value the two surfaces share is `PublishedGpa.of` (and `UsDate.of`,
`WholeDollars.spoken`).

Page-only strings: `AWARD_GRID_HEADING` (:138,
`"Automatic scholarships this
school publishes"`), `gridHeadingOf` suffixes
`"for students it classes as
in-state"` / `"out-of-state"` (:1575, :1579, shared
with the National Merit block through `publishedAudienceOf` :1564),
`NO_SCORE_EFFECT_LINE` (:158), `NO_SCORE_ROW_LABEL` (:161, `"no test score"`),
the unstated-requirement fallback
`"This school does not say whether a test score must be submitted."` (:1637),
the deadline sentence
`"Scores must be with this school by ${UsDate.of(deadline)}."` (:1642), and
`readOnCitationOf` (:1537–1540,
`"Source: $publisher, read on ${UsDate.of(readOn)}."`).

### Frozen

Nothing. `AWARD_KIND_SENTENCE` (:155) is page-only and printed once in
`tierKindsSection` (:2196), not in the block.

### Page tests on this block

- `a school where a score is money shows its published grid, its requirement and its deadline`
  (:2230): asserts the exact `<li>` strings
  `"Presidential — SAT 1200–1250 — $6,000 a year"` (:2255),
  `"Presidential — ACT 30–31 — $8,000 a year"` (:2256),
  `"Achievement — no test score, GPA 3.50 and above — $2,000 a year"` (:2258),
  the requirement (:2261), `"Scores must be with this school by Dec 4, 2026."`
  (:2262), `"Source: $GRID_PUBLISHER, read on Aug 1, 2026."` (:2263). **All
  three cell strings must be rewritten for a table.**
- `a school whose automatic table a score does not move still shows the table, and says so`
  (:2289): asserts `NO_SCORE_EFFECT_LINE` (:2313) and
  `"Trustee — GPA 3.75 and above — $4,000 a year"` (:2316).
- `the grid carries no student score, no band the student reaches and no point gap`
  (:2355): bans `"your student scored"`, `"your score"`, `"points away"`,
  `"qualifies for"`, `"would earn"` (:2380) and asserts
  `"Presidential Elite — SAT 1300–1600 — $10,321 a year"` (:2384).
- `what kind of number a published grid is, is said once for the page and not once per school`
  (:2388): `countOf(body, "report-award-grid") == 2`,
  `countOf(body, AWARD_KIND_SENTENCE) == 1`, and the sentence sits after
  `report-kinds` (:2404–2406).
- `a cell the school publishes in words renders those words, never a blank and never a zero`
  (:2409): asserts `"Presidential Elite — SAT 1500–1600 — tuition and fees"`
  (:2433) and no `"$0"` (:2436).
- `a school that publishes one grid per residency shows both, each scoped to the students it is for`
  (:2439): asserts the two headings with audience suffixes (:2465, :2469) and
  `countOf(body, "report-award-grid") == 2`.
- `a school's own words are quoted verbatim, and our own prose never says the word they use`
  (:2476): every occurrence of `"award"` in `visibleProseOf(body)` must fall
  inside a quoted band name or `awardAsPublished` span (:2505–2515). A column
  header "Award" would FAIL this; "What it pays" / "The school's name for it"
  would not.
- `no grid is shown where we have not read the school, it publishes none, or our reading is stale`
  (:2320).

### Smallest row seam

`AutomaticAward` + `List<AutomaticAwardBand>` already IS the row seam; no new
type is required. One grid = one table; one band = one row.

| fact in `bandLine`                       | field                                                                                                         |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| band name                                | `AutomaticAwardBand.awardName`                                                                                |
| `"SAT 1200–1250"`                        | `score as BandScoreAxis.Range` → `test.displayName`, `low`, `high`                                            |
| `"no test score"`                        | `score is BandScoreAxis.NoScore && scoreIsMoney` (`PrintableGrid.scoreIsMoney`)                               |
| `"GPA 3.50 and above"`                   | `gpaLow`, `gpaHigh` via `PublishedGpa.of`                                                                     |
| `"$6,000 a year"` / `"tuition and fees"` | `awardPerYearUsd` / `awardAsPublished` (one of the two non-null by `init`)                                    |
| heading audience                         | `AutomaticAward.residencyBasis` via `publishedAudienceOf`                                                     |
| `"Source: X, read on D."`                | `publisher`, `checkedOn` → caption                                                                            |
| requirement / deadline / no-score-effect | `scoreRequirementAsPublished`, `scoreDeadline`, `scoreIsMoney` → caption or a one-line note under the caption |

Note the grid is a per-CELL table in the store ("one row per CELL",
`AutomaticAward.kt:217–219`), so a score × GPA matrix renders as rows with a
score column and a GPA column, not as a 2-D grid. The order is the DAO's
(`CostReportPage.kt:1623–1626`); the page must not re-sort.

## nationalMeritBlocks / nationalMeritBlock / meritRowLines (`CostReportPage.kt:1753–1940`)

### Typed value

`CollegeCost.nationalMerit: List<NationalMeritPublishedAnswer>`
(`CollegeCostService.kt:458`), read from
`nationalMeritReader.publishedAnswersInSession` (:1455–1458).
`NationalMeritPublishedAnswer` (`NationalMeritService.kt:481`): `NonePublished`
(:489), `Expired` (:497), `Automatic(award, claim, published)` (:506),
`Competitive(award, claim, published)` (:514). `printableMeritRowOf`
(`CostReportPage.kt:1784–1810`) keeps the two paying arms whose `published` is
`PublishedPackage.Priced` and returns `PrintableMeritRow(award, claim, priced)`
(:1762–1767).

`PublishedPackage.Priced` (`NationalMeritService.kt:244`):
`baseRate:
PublishedBaseRate`, `perYearUsd: Int?`, `totalUsd: Int?`,
`years: Int?`, `asPublished: String`. `PublishedBaseRate` (:161):
`competitionYear: Int`, `funnel`, `publisher: String`, `checkedOn: LocalDate`,
`finalistShareOfEntrants: Share?` (:183). `NationalMeritClaim.Paying`
(`NationalMeritAward.kt:119`): `guarantee: AwardGuarantee` (:120),
`standing:
QualifyingStanding` (:122), `asPublished`, `awardName: String?`
(:125), `perYearUsd`/`totalUsd`/`years` (:126–128),
`firstChoice: FirstChoiceRule?` (:130;
`Required(deadline: LocalDate?, asPublished)` :41, `NotRequired(asPublished)`
:48), `applyDeadline: ApplyDeadline?` (:132; `ApplyDeadline(on, asPublished)`
:61). `NationalMeritAward` (:180): `entryYear: AcademicYear` (:184),
`residencyBasis` (:186), `publisher` (:190), `checkedOn` (:195).

### Copy seam and consumers

No shared sentence seam. Every sentence in `meritRowLines` (:1838–1847) and its
helpers is private to the page: `meritStandingLineOf` (:1857–1863,
`"For
${meritStandingOf(standing)} entering in ${entryYear.label}$audience."`),
`guaranteeLineOf` (:1866–1870 → `AUTOMATIC_MERIT_LINE` :180 /
`COMPETITIVE_MERIT_LINE` :183), `meritStandingOf` (:1873–1878, with the KDoc
"the chat tool's labels are the coach's, and say 'her'"), `meritFigureOf`
(:1887–1897, `"$17,500 a year, $70,000 in total over 4 years."`),
`firstChoiceLineOf` (:1900–1912, appends
`" Name it first choice by ${UsDate.of(it)}."` or `FIRST_CHOICE_UNSAID` :187),
`applyLineOf` (:1850,
`"Apply by ${UsDate.of(deadline.on)}. ${deadline.asPublished}"`), and
`baseRateSentenceOf` (:1924–1940,
`"About ${share.spokenPercent()} of
entrants in the ${baseRate.competitionYear} National Merit competition reach
Finalist. ${readOnCitationOf(baseRate.publisher, baseRate.checkedOn)}"`).
`NationalMeritChatTool` emits structured JSON (`publisher` :545, `checked_on`
:548, sponsor fields :368–374) and shares no sentence with the page.

Today's block is `<ul><li>` with seven `<p class="report-merit-*">` per row
(:1840–1846).

### Frozen

Nothing. `NATIONAL_MERIT_KIND_SENTENCE` (:175–177) is page-only, printed once in
`tierKindsSection` (:2198), followed by the base rate (:2199).

### Page tests on this block

- `an automatic row shows who it is for, that it is paid, the figures, the school's words and its first-choice rule`
  (:2626): asserts the heading (:2636),
  `"Source: $MERIT_PUBLISHER, read on Sep 25, 2026."` (:2637),
  `"For National Merit Finalists entering in 2027-28, for students it classes as in-state."`
  (:2639), `AUTOMATIC_MERIT_LINE` (:2642),
  `"$17,500 a year, $70,000 in total over 4 years."` (:2643), `MERIT_WORDS`
  verbatim (:2644),
  `"$MERIT_FIRST_CHOICE_WORDS Name it first choice by May 1, 2027."` (:2646) and
  NO `"of entrants"` inside the block (:2649). **The standing line and the
  figure line must be rewritten for a table.**
- `a competitive row is said as considered and not promised, and never as paid`
  (:2653): `COMPETITIVE_MERIT_LINE` present, `AUTOMATIC_MERIT_LINE` absent,
  `"For National Merit Semifinalists entering in 2027-28."` (:2660).
- `a row the school prices in words alone prints its words and no figure line, never a zero`
  (:2664): asserts `report-merit-figure` is ABSENT (:2673) and no `"$0"`
  (:2674). A table cell for a words-only row must therefore be a blank that is
  not `$0` and must not carry the class `report-merit-figure` — or the test is
  rewritten.
- `a National Merit row with an audience the page cannot name refuses the page rather than print it`
  (:2679): 503 via `CorruptAwardResidencyBasisException`
  (`NationalMeritService.kt:445`).
- `no block and no kinds sentence for an absence, a stale reading, a withheld price or no row at all`
  (:2692).
- `one stale row beside a live one prints only the live one` (:2716):
  `assertEquals(1, countOf(block, "<li>"))` (:2731). **Counts `<li>` — must
  become `<tr>` or a row class.**
- `an in-state and an out-of-state row both print, each labelled, and neither is compared with the family`
  (:2737): `countOf(block, "<li>") == 2` (:2757), the two audience suffixes
  (:2758–2759), both money strings `"$15,000 a year"`/`"$17,500 a year"`
  (:2760), and no `\bOR\b|Oregon|your state|lives in|not yours` (:2761).
- `the National Merit block says nothing about the student` (:2791): bans
  `"selection index"`, `"your student"`, `"scored"`, `"psat score"`,
  `"qualifies"` inside the block (:2798).
- `what kind of number a National Merit scholarship is, and the base rate, are said once for the page`
  (:2808): one `<section class="report-national-merit">` per school (:2821),
  `NATIONAL_MERIT_KIND_SENTENCE` once, and the exact base-rate string
  `"About 1.3% of entrants in the 2028 National Merit competition reach Finalist. Source: $MERIT_SPONSOR, read on Sep 1, 2026."`
  once, inside the kinds block (:2823–2830).
- `a school's own National Merit words are quoted verbatim, and our own prose never says the word they use`
  (:2837): same `"award"`-only-inside-quotation sweep as the grid.

### Smallest row seam

`PrintableMeritRow(award, claim, priced)` is already a row; the proposed table
has one row per `PrintableMeritRow` with these columns, every one of them
readable off existing fields:

| fact in today's lines    | field                                                                                                                                  |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| the school's name for it | `claim.awardName: String?` (blank cell when null, not invented)                                                                        |
| standing                 | `claim.standing: QualifyingStanding` via `meritStandingOf`                                                                             |
| entering class           | `award.entryYear.label` (`"2027-28"`, `AcademicYear.kt:67`)                                                                            |
| audience                 | `award.residencyBasis` via `publishedAudienceOf` (null → "everyone" is NOT a stored fact; today null renders as no clause, :1859–1861) |
| paid / considered        | `claim.guarantee` (`AUTOMATIC`/`COMPETITIVE`) — the two standing sentences :180, :183 move to the header or caption                    |
| per year / total / years | `priced.perYearUsd`, `priced.totalUsd`, `priced.years`                                                                                 |
| the school's words       | `priced.asPublished` (never absent)                                                                                                    |
| first choice             | `claim.firstChoice` (`Required.deadline`, `asPublished`; `NotRequired.asPublished`; null → `FIRST_CHOICE_UNSAID`)                      |
| apply by                 | `claim.applyDeadline?.on`, `.asPublished`                                                                                              |
| caption citation         | `award.publisher`, `award.checkedOn` (grouped by this pair at :1757)                                                                   |
| base rate (page-level)   | `priced.baseRate` — stays in `tierKindsSection` (:2199), never in the table (D3)                                                       |

The "one publisher per table" constraint is already the grouping key:
`groupBy { it.award.publisher to it.award.checkedOn }` (:1757).

## debtBlock / shownDebtSentence (`CostReportPage.kt:1965–1988`, `:2052–2072`)

### Typed value

`CollegeCost.medianDebtAtCompletionUsd: Int?` (`CollegeCostService.kt:326`) and
`cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)` (:632) →
`FigureStatusNote` (:801) with `status`, `statement: String?`,
`cell:
PublishedCell` (:838; `PublishedCell.source: MoneySource`,
`PublishedCell.kt:70`, `assurance: AssuranceTier` :76), `assurance`.

### Copy seam and consumers

The shown sentence is composed on the page (:2056–2058 and :2072):
`"${MoneySourceCopy.openingLabelOf(note.cell.source)} reports that students who
finished here carried a median of ${WholeDollars.spoken(debt)} in federal
loans. The source publishes no year for this figure."`
`MoneySourceCopy.labelOf` (`MoneySourceCopy.kt:34`) / `openingLabelOf` (:49) are
shared with `CollegeCostChatTool.kt:101` (`spokenListOf`) and
`FigureStatusCopy.statementOf` (`FigureStatusCopy.kt:107`). The blank arm uses
`blankFor(cost, field,
"This school does not report the federal loan debt students carried when they
finished.")`
(:1973–1979), which composes `FigureStatusCopy.statementOf` +
`AssuranceTierCopy.statementOf` for the four tiered statuses (see
`a blank cell says the status first and what kind of number it would have been
second`,
`CostReportPageTest.kt:707`, asserting `"$status $tier"` :728).

### Frozen

The six `FigureStatusCopy.agentlessStatementOf` sentences
(`FigureStatusCopy.kt:56–81`) ARE pinned against the served prompt:
`SystemPromptCatalogTest.kt:3283`
`the served figure-status paragraph recites every shipping status sentence`
iterates `FigureStatus.entries` and asserts `statuses.contains(spoken)`
(:3301–3308); the sentences also sit verbatim in
`prompts/coach-system-prompt.md` and every
`db/schema/seed-coach-system-prompt-v27.sql` … `v51.sql`. The page prints these
only inside blanks (`blankFor`, and `ProgramReturnCopy.sentenceOf`'s gap marks,
`ProgramReturnCopy.kt:209–211`). The federal-debt figure sentence itself
(`"students who finished here carried ..."`) is page-only.

### Page tests

- `debt context is rendered without a year, because no source dates it` (:679):
  `"$21,000 in federal loans"` and
  `"The source publishes no year for this figure"`.
- `the shown debt paragraph says what KIND of number it is, and it is not the college's`
  (:687): the administrative-record sentence is on the page (:695) and AFTER the
  figure's words (:700–703). Since RFC 191 the sentence is in
  `tierKindsSection`, so this ordering holds by section order.
- `the report says the federal debt figure undated and the school's own borrowing with its year`
  (:1228): `"carried a median of $21,000 in federal loans"` (:1240),
  `"The source publishes no year for this figure."` (:1241).
- `the federal debt names its own publisher while the borrowing block names the school's own filing`
  (:1426):
  `"$federalPublisher reports that students who finished here carried a median of $21,000"`
  (:1442), the federal publisher precedes the CDS header (:1455), and **the RFC
  175 D9 sweep**:
  `listOf("41,747", "64,865", "64,612", "22,865", "$253").forEach { assertFalse(body.contains(it), "the two debt figures were combined: [$it]") }`
  (:1459–1461; also at :1257–1259). These are the sum/difference of the
  fixture's `21,000` federal median and `20,747`/`43,865` CDS averages.
- `a median debt the publisher suppressed is not read as a school that does not report it`
  (:1582).
- `the tier sentence is said once for the figures it covers, not once per school`
  (:1671): `countOf(body, statement) == 1`, `"The median federal loan debt:"`
  scope present (:1678), `"reports that students who finished here carried"`
  still present (:1684).

### Smallest row seam

One figure, one publisher, one cohort — it is a one-row table or a caption line.
D9 forbids it sharing a table with the borrowing rows. Facts:

| fact                           | field                                                                                                   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------- |
| `$21,000`                      | `cost.medianDebtAtCompletionUsd`                                                                        |
| "federal loans" (the measure)  | `CostField.MEDIAN_DEBT_AT_COMPLETION_USD.spokenLabel` (`"the median federal loan debt"`, used at :1678) |
| publisher                      | `note.cell.source` via `MoneySourceCopy`                                                                |
| "The source publishes no year" | a property of the field, page-authored; must stay reachable (D1)                                        |
| tier                           | `note.assurance` → `tierKindsSection` already                                                           |

## programReturnBlocks / subjectReturnBlock / programParagraph (`CostReportPage.kt:2000–2033`)

### Typed value

`subjects: List<CollegeSubjectReturn>` from `ProgramReturn.forCollege`
(`ProgramReturn.kt:428`);
`CollegeSubjectReturn(subjectName, college:
ProgramReturnCollege)` (:437).
`ProgramReturnCollege` (:355): `WithPrograms(…,
headProgram, tailPrograms)`
(:367) / `NoProgram` (:378). `ProgramReturnProgram` (:274): `cipCode`, `title`
(:283, verbatim CIPDESC with trailing period), `WithFigures.figures` (:299; one
entry per `ProgramMoneyMeasure` by construction) / `NoPublishedFigures` (:343).
`ProgramReturnFigure` (:166): `Reported(amountUsd, provenance)` (:191),
`ImputedByPublisher` (:206), `Suppressed` (:214), `NotReported` (:221),
`NotApplicable` (:228), `NotCollectedByUs` (:235), `NoFigure(measure)` (:258).
`ProgramFigureProvenance` exposes
`cohortFirstYear`/`cohortLastYear: AcademicYear` (:95, :98), `publisher: String`
(:101), `assuranceTier` (:104), `inflationBaseYear:
CalendarYear?` (:123), and
`variable.publishedBy: FieldOfStudyPublisher`.

### Copy seam and consumers

`ProgramReturnCopy` (`ProgramReturnCopy.kt`) is the seam. The page calls
`subjectLeadOf` (:113; `"$subjectName, which your student wants to study:"`),
`subjectLayoutOf` (:137 → `SubjectLayout.Mark(statement)` / `Programs(programs)`
:120–131), `programLabelOf` (:163; title minus trailing `.` plus `:`),
`programSentencesOf` (:170) → `sentenceOf` (:192), and `COVERAGE_SENTENCE` (:56)
and `SOURCES` (:99, a `LinkedSentence` :308). The coach payload uses ONLY the
three constants `NO_PROGRAM_STATEMENT` (:33), `NO_PUBLISHED_FIGURES_STATEMENT`
(:36), `NO_FIGURE_STATEMENT` (:39) at `CollegeCostChatTool.kt:137, :167, :189`;
every figure reaches the coach structured — `amount_usd`, `status`, `publisher`,
cohort first/last year, `cohort_sentence`, `inflation_base_year`
(`CollegeCostChatTool.kt:206–225`). So `valuedSentenceOf` (:243–264), `cohortOf`
(:266–270), `markLabelOf` (:230–236), the two "withheld by … to protect
students' privacy" marks (:154, :185) and `spokenPublisherOf` (:285–289) are
**page-only sentences in shared code** — the coach never says them.

Sentences today (`valuedSentenceOf` :249–262):

- `"Graduates working four years after finishing earned a median of $dollars a year, according to $publisher ($cohort)."`
- `"Graduates who borrowed federal loans finished with a median federal loan debt of $dollars, according to $publisher ($cohort)."`
- `"For graduates who borrowed federal loans, the median monthly payment on a standard 10-year plan is $dollars, by $publisher's own estimate ($cohort)."`

where `$cohort` =
`"students who finished in 2017-18 and 2018-19, in 2024 dollars"` (:266–270,
:272–280).

Gap marks (:200–219):
`"Median earnings: withheld by the U.S. Treasury to
protect students' privacy."`,
`"${markLabelOf(measure)}:
${FigureStatusCopy.agentlessStatementOf(status)}"`
(the frozen status sentence after a colon),
`"${markLabelOf(measure)}: $NO_FIGURE_STATEMENT"`.

### Frozen

`FigureStatusCopy.agentlessStatementOf` sentences inside the
`NotReported`/`NotApplicable`/`NotCollectedByUs` marks (:209–211) — frozen by
the prompt pin above. The three `ProgramReturnCopy` constants are pinned by
`SystemPromptCatalogTest.kt:2206`
(`the served program-return paragraph reads
the section and keeps the three silence rules`)
and by the tool legend (`CollegeCostChatTool.kt:2237–2271`). The figure
sentences are not frozen.

### Page tests (`CostReportProgramReturnTest.kt`)

- `with no major answer on the profile the page says nothing about it` (:134):
  no `report-program`, no `COVERAGE_SENTENCE`, no `SOURCES.lead`, no Scorecard
  link.
- `an answer with no matching program anywhere prints no coverage, sources or Scorecard link`
  (:144): `NO_PROGRAM_STATEMENT` present.
- `every subject appears in stored order, and every program by its published title in the answer's own order`
  (:155):
  `paragraphsOf(section, "report-program").map { it.substringBefore(':') + ":" } == listOf("Biology, General:", "Ecology, Evolution, Systematics:", "Biochemistry:", "Chemistry:")`
  (:162–166). **Reads `<p class="report-program">` — must be rewritten for
  rows.**
- `each shown figure carries its own publisher and cohort in its own sentence`
  (:169): asserts `ProgramReturnCopy.sentenceOf(figure)` for each of three
  figures (:175) AND the literal fragments
  `"$41,200 a year, according to the U.S. Treasury (students who finished in 2017-18 and 2018-19, in 2024 dollars)"`
  (:179),
  `"$26,000, according to the federal student loan system (students who finished in 2018-19 and 2019-20)"`
  (:185),
  `"$291, by the federal student loan system's own estimate (students who finished in 2018-19 and 2019-20)"`
  (:189). **All must be rewritten.**
- `each gap renders its own mark, and a subject with every figure withheld renders one`
  (:194):
  `"Median earnings: withheld by the U.S. Treasury to protect students' privacy."`
  (:199), `"Median federal loan debt: This school did not report this figure."`
  (:203), `"Monthly payment on a standard 10-year plan: ${NO_FIGURE_STATEMENT}"`
  (:205), `"Biochemistry: ${NO_PUBLISHED_FIGURES_STATEMENT}"` (:206), the
  Eastbrook `report-program-subject` paragraphs (:208–213), the Wynnford
  one-mark sentence (:215–221), `countOf(proseOf(wynnford), "withheld") == 1`
  (:222), no `"Zoology"` (:223).
- `the coverage sentence is said once on a three-school page` (:227).
- `a page with every program figure withheld and no school-wide debt says the tier sentence once, over the field-of-study figures`
  (:246): the scope
  `"The median earnings by field of study, the median federal loan debt by field of study and the monthly loan payment by field of study: $adminRecord"`
  (:257–259).
- `a page with every program figure withheld beside a school-wide debt figure says the tier sentence once`
  (:265).
- `every woven paragraph sits before the school's borrowing header` (:277):
  `section.lastIndexOf("report-program") < section.indexOf(BorrowingWire.HEADER_TAIL)`
  (:282–283).
- `the figures by field of study are covered by the one administrative-record sentence`
  (:288): exact scope
  `"The median federal loan debt, the median earnings by field of study, the median federal loan debt by field of study and the monthly loan payment by field of study:"`
  (:295–297).
- `no figure comparison word appears anywhere on a page carrying every arm`
  (:303): `figureComparisonWordsIn(visibleProseOf(...))` is empty; the list is
  `"higher", "lower", "less often", "more often", "gap", "worse odds"`
  (`college/src/testFixtures/.../FigureComparisonWords.kt:24–25`), word-bounded
  and case-insensitive (:33–34). A column header "Gap" would fail.
- `the page links each filing and the College Scorecard once, and nothing else`
  (:315): exactly three anchors in page order (:320–327), all
  `rel="noreferrer"`, and the `SOURCES` sentence with its anchor inside
  `report-sources` (:330–333).
- `every measure has a sentence and a name in the copy` (:338).

### Smallest row seam

Proposed: one table per (school, subject) — the subject lead becomes the caption
— with one row per (program, measure), or one row per program with one column
per measure. Columns per measure is the natural shape because every
`WithFigures` program carries exactly one entry per `ProgramMoneyMeasure`
(`ProgramReturn.kt:299`, "EVERY measure gets one"). But the three measures have
two publishers and two cohorts (`ProgramReturnCopy.kt:243–264`), so "one table
per publisher" forces either (a) one table per publisher per subject — an
earnings table (Treasury) and a debt/payment table (NSLDS) — or (b) a publisher
column with the caption naming both. The brief should decide; (a) satisfies the
fixed constraint literally and keeps the per-publisher cohort in the caption.

| fact in `valuedSentenceOf` / marks                                     | field                                                                                                                                                           |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| program title                                                          | `ProgramReturnProgram.title` via `programLabelOf` (row header)                                                                                                  |
| `$41,200`                                                              | `ProgramReturnFigure.Valued.amountUsd`                                                                                                                          |
| measure name                                                           | `measure` via `spokenLabelOf` (:222) or `markLabelOf` (:230, currently private)                                                                                 |
| publisher                                                              | `provenance.variable.publishedBy` via `spokenPublisherOf` (:285, private) → caption                                                                             |
| cohort years                                                           | `provenance.cohortFirstYear`, `cohortLastYear` via `spokenYearsOf` (:272, private) → caption                                                                    |
| dollar base                                                            | `provenance.inflationBaseYear` → caption (earnings only)                                                                                                        |
| "the publisher's own estimate" (payment measure)                       | wording of `valuedSentenceOf` for `MEDIAN_DEBT_10YR_MONTHLY_PAYMENT` (:258–261); `ImputedByPublisher` has its own suffix (:196–199) → a status cell or footnote |
| suppressed / not reported / not applicable / not collected / no figure | `figure` arm → blank cell with the status sentence (frozen for the three agentless ones)                                                                        |
| `NO_PROGRAM_STATEMENT` / `NO_PUBLISHED_FIGURES_STATEMENT`              | `ProgramReturnCollege.NoProgram` / `ProgramReturnProgram.NoPublishedFigures` → a one-cell row or a caption note                                                 |
| "Every matching program's figures are withheld by X"                   | `SubjectLayout.Mark` (:149–156) — a derived collapse; a table can keep it as a single-row table or drop the collapse (every cell would read "withheld")         |

Shared source: `ProgramFigureProvenance` and `ProgramReturnFigure` are what
`CollegeCostChatTool.putHeldProgramFigure` already reads
(`CollegeCostChatTool.kt:206–225`), so a row seam reading the same fields cannot
drift from the coach's payload. To expose `markLabelOf`, `spokenPublisherOf` and
`spokenYearsOf` as cell/caption helpers they would need to become non-private
members of `ProgramReturnCopy` (or a sibling `ProgramReturnRows`).

## borrowingParagraphs (`CostReportPage.kt:2225–2258`)

### Typed value

`CollegeCost.borrowing: BorrowingCoverage` (`CollegeCostService.kt:407`), read
from `BorrowingDao.listLatest` (:1418–1422). Only
`BorrowingCoverage.Reported(figures: BorrowingAtGraduation,
gapStatusByLoanType: Map<LoanType, FigureStatus>, graduatingClassGapStatus:
FigureStatus?)`
(`BorrowingCoverage.kt:76–94`) renders; `NoFiling`, `NoBlock`, `NotReadByUs`
return before any section (`CostReportPage.kt:2229–2235`).
`BorrowingAtGraduation` (`BorrowingAtGraduation.kt:22`): `graduatingClass: Int?`
(:24), `byLoanType: Map<LoanType, LoanTypeBorrowing>` (:26),
`source:
CdsCitation` (:27).
`LoanTypeBorrowing(averageDebtUsd: Int?, borrowers:
BorrowerCounts?)`
(`CollegeBorrowing.kt:203–205`);
`BorrowerCounts.Counted(borrowers,
graduatingClass)` with `share: Share?`
(:239–243) or `ContradictsGraduatingClass` (:256, never spoken).

### Copy seam and consumers

`BorrowingWire.blockOf(coverage): BorrowingBlock` (`BorrowingWire.kt:250`) →
`BorrowingBlock(citation: CdsCitation, sentences: List<String>)` (:39–43) with
`header` derived (:55). The page renders `sourceLink(block.citation)` +
`"${BorrowingWire.HEADER_TAIL}."` (:2250–2253) and then each sentence with a `.`
appended (:2258). `HEADER_TAIL` (:363):
`" reports these figures for the
students who graduated from it that year -- not this year's freshmen, and not
all its undergraduates"`.

Sentences `blockOf` can emit, in order (:250–262, :266–286):
`graduatingClassNotReadByUsLabel()` (:494), then per `LoanType.SPOKEN_ORDER`
(federal, private, then the rest; `LoanType.kt:126–131`) `averageDebtLabel`
(:386,
`"Graduates who borrowed ${loanType.spoken} owed ${WholeDollars.spoken(amountUsd)} on average by the time they graduated"`),
`shareLabel` (:404,
`"${counted.borrowers} of the ${counted.graduatingClass} students who graduated borrowed ${loanType.spoken} -- " + share.spokenPercent()`),
`gapLabel` (:433) for `NOT_COLLECTED_BY_US` / `SUPPRESSED_BY_PUBLISHER` /
`NOT_APPLICABLE`; then `privateNotFiledLabel()` (:505,
`"This filing reports no
average for private loans, so these figures are not the whole of what its
graduates borrowed"`).
`LoanType.spoken` (:513–521): `"a loan of any kind"`, `"federal loans"`,
`"a loan from the school itself"`, `"a state loan"`, `"a private loan"`.

**`blockOf` is page-only.** Its only caller in any main source set is
`CostReportPage.kt:2243` (grep of `blockOf` across `service`, `rest-server`,
`public-web` main). The coach reads `BorrowingWire.objectOf(borrowing)`
(`CollegeCostChatTool.kt:803`), which emits the numbers under typed keys
(`federal_loan_average_debt_usd`, `_borrower_count`,
`_share_who_borrowed_percent`, `graduating_class_count`) AND the same label
sentences under `_label` keys (`BorrowingWire.kt:175–177, :196–236`), plus
`cohort_label` = `blockHeader(source)` (:199) and `NOTE` (:529). The two share
`listSentencesFor`'s policy by construction (:266). The coach prompt
(`prompts/coach-system-prompt.md:295–297`) instructs the coach to say the cohort
"with its year" and to "name the school as the one saying it"; it does not
recite the label sentences. `SystemPromptCatalogTest.kt:2856` pins only
`BorrowingWire.KEY`, the cohort phrasing and
`"Never add two kinds of loan
together"` in the prompt.

`BorrowingWireTest.kt` pins the header split (`:52, :60, :73`), not the per-loan
sentences. `CollegeCostChatToolTest.kt` and `CostReportPageTest.kt` pin the
literals `"on average by the time they graduated"` and
`"reports these figures
for the students who graduated"` (grep).

### Frozen

Nothing in `BorrowingWire` is pinned against a prompt row.
`FigureStatusCopy.agentlessStatementOf` is spliced into `gapLabel` for the
withheld / not-applicable arms (:468–480) — that inner sentence is frozen; the
frame around it is not.

### Page tests

- `the report says the federal debt figure undated and the school's own borrowing with its year`
  (:1228): the linked header
  `"rel=\"noreferrer\">Borrowing College's 2024-25 Common Data Set</a> reports these figures for the students who"`
  (:1247–1251), `"Graduates who borrowed federal loans owed"` (:1253), the D9
  sum sweep (:1257–1259), and
  `"never added together and never compared with each other"` in the body
  (:1262).
- `a school that filed no private figure is said to have filed none` (:1268):
  `"reports no average for private loans"` (:1286),
  `BorrowingWire.privateNotFiledLabel()` whole (:1291), no `"$43,865"` (:1294).
- `a private cell we could not read is said as OUR gap, beside the federal figure we did read`
  (:1298): `"$20,747"` (:1320),
  `BorrowingWire.notReadByUsLabel(LoanType.PRIVATE)` whole (:1323), NOT
  `"reports no average for private loans"` (:1327).
- `every borrowing figure on the page renders under the block's one attribution`
  (:1332): pins `borrowingMarkers` =
  `"reports these figures for the students who graduated"`,
  `"on average by the time they graduated"`, `"borrowed federal loans"`,
  `"reports no average for private loans"` (:1554–1560) against
  `block.header + block.sentences` (:1380–1384); asserts
  `block.sentences.filter { body.contains(it) } == block.sentences` (:1393–1398)
  — **every wire sentence must appear verbatim in the body**; the linked header
  appears (:1409–1411); every sentence is AFTER the header (:1412–1416); no
  sentence contains `"Common Data Set"` (:1419–1423).
- `the federal debt names its own publisher while the borrowing block names the school's own filing`
  (:1426): the linked header (:1447–1448), header excludes the federal publisher
  (:1453), federal publisher precedes the header (:1455), the D9 sweep
  (:1459–1461).
- `the page and the coach speak the loan types in ONE order, federal first`
  (:1465): `sentences == sentences.sortedBy { body.indexOf(it) }` (:1486–1490) —
  **again requires every wire sentence verbatim in the body** — and
  `body.indexOf("borrowed federal loans") < body.indexOf("borrowed a loan of any kind")`
  (:1492).
- `a filing whose only borrowing cells contradict each other renders no borrowing section`
  (:1498): none of `borrowingMarkers` in body (:1533).

The two tests at :1393 and :1486 are the ones a table conversion cannot keep
green without either (a) keeping the sentences on the page or (b) rewriting them
to assert rows. They are the spine of RFC 190's "the page renders exactly the
wire's list" guarantee, so (b) must replace the guarantee with its row
equivalent: every `LoanRow` the seam yields is rendered, in `SPOKEN_ORDER`,
under one caption that carries the citation.

### Smallest row seam

Proposed, beside `blockOf` in `BorrowingWire`:

```
data class BorrowingRows(
  val citation: CdsCitation,                 // caption: sourceLink + HEADER_TAIL
  val graduatingClass: Int?,                 // caption or a "graduates" column
  val graduatingClassNotReadByUs: Boolean,   // caption note (graduatingClassNotReadByUsLabel)
  val rows: List<LoanRow>,                   // LoanType.SPOKEN_ORDER, every type the filing speaks
  val privateNotFiled: Boolean,              // caption note (privateNotFiledLabel)
)
data class LoanRow(
  val loanType: LoanType,                    // row header via LoanType.spoken (currently private, :513)
  val averageOwedUsd: Int?,                  // averageDebtLabel's amount
  val borrowers: Int?, val graduates: Int?,  // shareLabel's two counts (Counted only)
  val share: Share?,                         // shareLabel's percent
  val gap: FigureStatus?,                    // gapLabel's status (ours / publisher's / not applicable)
)
```

| fact in today's sentence                                                  | field                                                                                                       |
| ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `citedAs` + `HEADER_TAIL`                                                 | `citation` (caption)                                                                                        |
| "Graduates who borrowed federal loans"                                    | `loanType`                                                                                                  |
| "owed $20,747 on average by the time they graduated"                      | `averageOwedUsd` (column header carries "owed on average by graduation")                                    |
| "394 of the 1,200 students who graduated borrowed … -- 32.8%"             | `borrowers`, `graduates`, `share` — the pair travels together (`BorrowerCounts.Counted`)                    |
| `notReadByUsLabel` / withheld / not applicable                            | `gap` → blank cell with the owner's sentence (`FigureStatusCopy` frozen text for the publisher/source arms) |
| `graduatingClassNotReadByUsLabel`                                         | `graduatingClassNotReadByUs`                                                                                |
| `privateNotFiledLabel`                                                    | `privateNotFiled` (`privateAverageIsUnfiled()` :296, currently private)                                     |
| "never add two of them together", "not a price" (`NOTE` :529, coach-only) | already said for the page in `sourcesSection` (:2348–2355)                                                  |

`BorrowingWire.objectOf`, `blockOf` and the new `rowsOf` would all read
`coverage.figures.of(loanType)`, `BorrowerCounts.Counted.share` and
`gapKey`/`privateAverageIsUnfiled`, so the three cannot narrate one filing
differently. `graduatingClassLabel` (:374) is emitted on the wire (:201–202) and
never on the page today — a table with a graduates column makes that figure
reachable on the page for the first time.

Caution for the sum sweep: the fixture's D9 forbidden strings (`"41,747"`,
`"64,865"`, `"64,612"`, `"22,865"`, `"$253"`) are sums/differences of `21,000`
with `20,747` and `43,865`; a graduates column (`1,200` or similar in the
fixture) adds new numbers to the page but none of the forbidden ones.

## tierKindsSection (`CostReportPage.kt:2160–2206`)

Inputs: `proseFiguresOf` (:2092–2095) = the shown federal debt figure
(`debtFigureOf` :2097–2105, a
`CoveredFigure(collegeName, FigureIdentity.Cost(field), SpokenAssurance)`) plus
`ProgramReturnCopy.coveredFiguresOf` (:2112–2115); `spokenElsewhere` =
`blankFiguresOf` (:2134–2145, the four tiered blank statuses).
`AssuranceTierGrouping.groupsOf` (`AssuranceTierGrouping.kt:46`) returns
`SpokenTierGroup(assurance, scope)` (:184) whose `statement` (:192) is
`AssuranceTierCopy.statementOf(tier, FilingLink.LINKED)` byte for byte via
`SpokenAssurance.statement` (`CollegeCostService.kt:937`).

Rendered: `h2 { +KINDS_HEADING }` (:2182, `"What kind of numbers these are"`),
one `p("report-kind")` per group with
`span("report-kind-scope") { +group.scope }` then the statement (:2183–2191);
then `AWARD_KIND_SENTENCE` if any grid prints (:2196),
`NATIONAL_MERIT_KIND_SENTENCE` + `baseRateSentenceOf(meritRows)` (:2198–2199),
`ProgramReturnCopy.COVERAGE_SENTENCE` (:2204). Absent when nothing qualifies
(:2179).

**What is frozen here.** The three tier sentences
(`AssuranceTierCopy.kt:105–131`, `:145`, `:155`) are the "byte-frozen" copy the
brief refers to. The freeze is pinned by `AssuranceTierCopyTest.kt:113–118`
(`the self-report sentence is
byte-identical to the frozen literal`), :135–143
(strict-prefix rule) and :171–194 (the promise sentence written exactly once in
`service/src/main`). The sentences do **not** appear in
`prompts/coach-system-prompt.md` or any
`db/schema/seed-coach-system-prompt-v*.sql` (repo-wide grep for
`"No one checks
it"` finds only `AssuranceTierCopy.kt`, its test, three RFCs and
`product/STATUS.md`); `SystemPromptCatalogTest` references the assurance
sentence only indirectly (`:1657` asserts the answer-shape paragraph contains
`"what kind of number a school's figures are"`). So "frozen against the v19 row"
is the KDoc's claim (`AssuranceTierCopy.kt:124–128`); the executable freeze is
`AssuranceTierCopyTest`. Either way the brief's constraint stands: the sentences
are not reworded. They are already said once per page, so a table-first page
keeps this section as is.

Tests that constrain this section: `the tier sentence is said once ...` (:1671),
`every figure the page speaks a kind for is covered by exactly one sentence`
(:1689), `our own at-home assumption is never swept under a publisher's tier`
(:1721), `a figure with no number is in no tier block, and keeps its own words`
(:1738), `the tier block ranks nothing and counts nothing` (:1763: no digit in
any scope :1773, none of `"strongest"`, `"weakest"`, `"most reliable"`,
`"rank"`, `"score"`, `"rating"`, `"tier 1"` in the block :1776–1778 — note the
base-rate sentence `"About 1.3% of entrants in the 2028 ..."` contains digits
but is not a `report-kind-scope`), `two schools on two tiers get two headers`
(:1795), `a bare label is refused ...` (:1832).

If the brief moves per-figure hedges here (e.g. "a grant the student never pays
back", "the publisher's own estimate"), the new sentences are page-authored and
must not contain a digit inside a scope span or any of the banned ranking words.

## sourcesSection (`CostReportPage.kt:2335–2368`)

`h2 { +"Sources and what this is not" }` (:2340); one paragraph composed of
`moneyAttributionSentence(sources)` (:2347, :2384–2385 →
`"The cost, price and
federal debt figures come from ${MoneySourceCopy.spokenListOf(sources)}. "`)
followed by the hand-written
`"The merit and borrowing figures come from each
school's own Common Data Set, cited beside them. The federal debt figure and
the school's own borrowing figures are different measures from different
publishers over different groups of students, so they are never added together
and never compared with each other."`
(:2348–2355); `fieldOfStudySources()` when a program prints (:2357, :2393–2403,
the page's one Scorecard anchor); then
`"These are averages for past students at each school. They are not an offer,
and no figure here is a price this family has been quoted."`
(:2358–2362) and
`"Only a school's own financial aid offer is a price for this family. Loans and
work-study are never subtracted from any price here."`
(:2364–2368).

Tests: `the page names its sources and says what it is not` (:479; asserts the
derived attribution :487, `"Common Data Set"`, `"They are not an offer"`,
`"Only a school's own financial aid offer is a price for this family"`), :1262
(`"never added together and never compared with each other"`), the three
IPEDS/attribution tests (:509, :561, :610, :654), and
`CostReportProgramReturnTest.kt:315` (the Scorecard anchor inside its sentence).
This section already states the D9 rule once for the page; a table-first page
leaves it alone. Note the sentence says "merit and borrowing figures come from
each school's own Common Data Set" and does not mention the two hand-typed
corpora (automatic scholarships, National Merit), whose publishers are named per
block by `readOnCitationOf`.

## CSS and markup

- `DATA_TABLE_CLASS = "data-table"` and `DATA_LABEL = "data-label"` are the two
  constants (`public-web/.../render/DataTable.kt:17`, `:31`); the KDoc states
  the contract: "A data table is a grid on a laptop and one card per row on a
  phone, each value re-labelled with the column it came from (`data-label`)"
  (:7–9).
- The grid half: `.data-table { width: 100%; border-collapse: collapse; }`
  (`site.css:271–274`), cells
  `border-bottom: 1px solid var(--border); padding:
  0.5rem 0.6rem; text-align: left; vertical-align: top;`
  (:276–282), header row quiet `font-size: 0.85rem; color: var(--muted)`
  (:285–288).
- The stacking half, `@media (max-width: 640px)` (:649):
  `.data-table, tbody,
  tr, th, td { display: block; width: 100%; }`
  (:650–657), `thead { display:
  none; }` (:659–661), `tr` gets a bottom border
  (:663–666), and the label mechanism
  `.data-table [data-label]::before { content: attr(data-label) ": ";
  color: var(--muted); font-size: 0.85rem; }`
  (:675–679). Report-specific:
  `.report-table .report-value { text-align: right; font-variant-numeric:
  tabular-nums; }`
  (:338–341) and left-aligned when stacked (:683–685).
- **The report DOES set `data-label` today**, on every value cell: `valueCell`
  writes `attributes[DATA_LABEL] = columnLabel` (`CostReportPage.kt:1375`) and
  `summaryRow` writes it on the way-of-living cell (:787). The whole-school rows
  deliberately omit it (:1024 comment). Every header uses
  `th(scope = ThScope.col)` / `ThScope.row` (9 occurrences; `rowHeader`
  :1360–1362).
- **No table on the report has a `<caption>`.** The only `caption {` in
  `public-web/src/main` is `ExplainerPage.kt:599` inside `renderCitedTable`.
- `/fafsa`'s cited table (RFC 238):
  `ExplainerBlock.CitedTable(caption,
  columns: List<TableColumn>, sourceColumn: TableColumn, rows: List<CitedRow>)`
  (`ExplainerPage.kt:128–134`) with `init` guards — non-blank caption, distinct
  headers, non-empty rows, row arity (:139–159). `renderCitedTable` (:597–603,
  `private`) emits
  `table("$DATA_TABLE_CLASS guide-table") { caption { ... };
  thead { citedHeaderRow }; tbody { ... } }`;
  `labelledCell` (:619–628) writes `attributes[DATA_LABEL] = label` on every
  data cell; the last cell is the row's own citation (:611–616).
  `.guide-table caption { text-align: left; font-weight:
  600; padding-bottom: 0.4rem; }`
  (`site.css:483–487`). `CitedRow` carries
  `source: ExplainerSource(publisher, title, url, fetchedOn)` and a
  `sourceUrlStatus` (:228–251; `ExplainerSource` :45–49) — a per-ROW citation,
  which is the wrong grain for this page (one citation per TABLE, in the
  caption). `FafsaPage.kt:370–412` builds one `CitedTable` per region with
  `caption = region`.
- Reusable as-is: the `data-table` class, `DATA_LABEL`, `th scope`, the caption
  CSS pattern. Not directly reusable: `CitedTable`/`CitedRow` (per-row citation,
  `authored` column sweep tied to the explainer's unsourced-prose rule,
  `ExplainerBlock` is used only by `FafsaPage.kt` and `WhoPaysPage.kt`). A
  report-side `caption` rule would need adding under `.report-table` (the
  `.guide-table caption` rule is scoped to that class).
- `web-common` contains only `ed/unicoach/web/common/`; no table helper lives
  there (`ls web-common/src/main/kotlin/ed/unicoach/web/` → `common`).

## Live branches

`git worktree list` (root `main` at `2aad6f8b`) and the four branch globs give
eight named branches plus one `claude/*` branch and two detached worktrees.
`git rev-list --count main..<b>` / `git diff --name-only main...<b>`:

| branch                                   | ahead / behind main | touches brief files?                                            |
| ---------------------------------------- | ------------------- | --------------------------------------------------------------- |
| `pipeline/rfc-253`                       | 0 / 26              | no (0 files)                                                    |
| `pipeline/rfc-258`                       | 8 / 4               | **`service/.../costs/CollegeCostChatTool.kt`** (83 files total) |
| `quality/centralize-primitives`          | 0 / 616             | no                                                              |
| `quality/code-review-skills`             | 0 / 540             | no                                                              |
| `ship/summary-holds-planned-arrangement` | 0 / 56              | no                                                              |
| `docs/0015-the-tight-loop`               | 1 / 78              | no (4 `product/0015-*` files)                                   |
| `docs/0016-the-report-at-a-glance`       | 0 / 0               | no                                                              |
| `docs/rfc-conventions`                   | 0 / 774             | no                                                              |
| `claude/xenodochial-dewdney-598fa5`      | 0 / 610             | no                                                              |
| detached `52a65f73`, `1bf653dc`          | 0 / 516, 0 / 514    | no                                                              |

No live branch touches `CostReportPage.kt`, `site.css`, `BorrowingWire.kt`,
`ProgramReturnCopy.kt`, `MeritAidWire.kt`, `CostReportPageTest.kt`,
`CostReportProgramReturnTest.kt`, `DataTable.kt` or `ExplainerPage.kt`.

`pipeline/rfc-258` (eight `wip(impl/N)` checkpoints, "parent coach" / supporter
coaching) rewrites `CollegeCostChatTool.kt` wholesale: the hunk header is
`@@ -72,1392 +72,1401 @@` and it moves `profileObject` to a public
`payloadOf(profile)` so the new `FamilyCostPictureChatTool`
(`service/.../family/FamilyCostPictureChatTool.kt` on that branch, :14, :57,
:61) can share the payload builder. It does not add callers of `blockOf`,
`shareLabel`/`averageLabel` or `ProgramReturnCopy`'s sentence functions. A
CONFLICTS edge exists only if a 0016 slice edits `CollegeCostChatTool.kt`; a
slice that adds `rowsOf` beside `blockOf` in `BorrowingWire.kt` and reads it
from the page does not collide. It is a second coach-side consumer of the cost
payload to keep in view for "one figure seam, two renderings".

## Measurement test

`CostReportPageTest.kt:1096–1113`:

```kotlin
fun `a three-school report's word count is measured and printed, never asserted`() {
  // Brief 0009 D20 deleted the ceiling this test used to assert (RFC 203).
  // `WORD_CEILING` moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404, the last
  // step UPWARD: every slice ratcheted it to whatever it had just measured, so
  // a threshold edited by the change it constrains asserted nothing and taxed
  // the next honest sentence. The MEASUREMENT is worth keeping and the GATE is
  // not, so this prints the number and lets a person judge growth at the /ship
  // gate, where the value the words buy can actually be weighed.
  //
  // Read it out of the captured standard output of this test:
  //   grep -h MEASURE public-web/build/test-results/test/*.xml
  val body = render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()))

  val words = visibleWordsOf(body)
  println(
    "[MEASURE] three-school cost report: [$words] visible words " +
      "(RFC 203 deleted the ceiling; it had moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404)",
  )
}
```

`visibleWordsOf` (:161–164):

```kotlin
private fun visibleWordsOf(body: String): Int {
  val text = visibleProseOf(body)
  return text.split(Regex("\\s+")).count { it.isNotBlank() }
}
```

`ReportProse.kt` (shared by both page tests): `reportSectionOf(body)` asserts
the `<section class="report"` marker and slices to the last `</section>`
(:14–18); `proseOf(html)` strips tags, decodes `&#39;`, `&quot;`, `&lt;`,
`&gt;`, then `&amp;` last, and collapses whitespace (:24–34);
`visibleProseOf(body) =
proseOf(reportSectionOf(body))` (:37);
`countOf(text, needle)` (:40–43). The fixture is the three `verbose*` schools
(:170–226), each with a merit filing and a borrowing filing and no grid,
National Merit or program figures. A prose-share criterion in the same style
would compute `proseOf` over the report section with `<table>…</table>` removed
versus the whole, and a currency-per-paragraph criterion would count
`Regex("\\$[0-9,]+")` matches inside each `<p>` of the section; both would
`println("[MEASURE] ...")` and assert nothing, per brief 0009 D20.

## Findings that change the plan

1. **The "byte-frozen" tier sentences are not in any prompt row.** The three
   `AssuranceTierCopy` sentences are frozen by
   `AssuranceTierCopyTest.kt:113–118` (a literal) and the one-place sweep
   (:171–194); no `seed-coach-system-prompt-v*.sql` or
   `prompts/coach-system-prompt.md` carries them. What `SystemPromptCatalogTest`
   pins verbatim against the served prompt is the six
   `FigureStatusCopy.agentlessStatementOf` sentences (`:3283–3308`). The page
   prints those only in blanks (`blankFor`) and in `ProgramReturnCopy`'s gap
   marks (`ProgramReturnCopy.kt:209–211`). The constraint "frozen sentences
   untouched" therefore binds the status sentences inside blank table cells as
   much as the tier sentences in the kinds section.

2. **Only the borrowing block has a shared sentence builder the page prints
   verbatim and tests pin as a list.** `BorrowingWire.blockOf` is page-only, but
   `CostReportPageTest.kt:1393–1398` and `:1486–1490` assert every
   `block.sentences` string is in the body and in wire order. These two tests
   must be rewritten to assert rows, not kept.
   `MeritAidWire.shareLabel/averageLabel` are coach-shared and the page can stop
   printing them without touching the coach (the wire still emits
   `share_label`/`average_label`). `ProgramReturnCopy`'s figure sentences,
   cohort phrase and gap marks are page-only sentences in shared code; the coach
   gets structured fields.

3. **The grid and National Merit blocks have no copy seam at all** — every
   string is private to `CostReportPage.kt`, and the values are already row
   types (`AutomaticAwardBand`, `PrintableMeritRow`). Converting them is pure
   page work plus test rewrites of the exact `<li>` strings
   (`CostReportPageTest.kt:2255–2258, :2316, :2384, :2433` and
   `:2639, :2643,
   :2646, :2660`) and the two `countOf(block, "<li>")`
   assertions (:2731, :2757).

4. **Two sweeps constrain column headers and captions.** The `"award"` sweep
   (`:664–676` page-wide; `:2505–2515` and `:2837–2850` prose-wide except inside
   quoted school words) forbids a header "Award"; `figureComparisonWordsIn`
   (`CostReportProgramReturnTest.kt:303`) forbids `"gap"`, `"higher"`,
   `"lower"`, `"more often"`, `"less often"`, `"worse odds"` as words anywhere
   in the prose. `the tier block ranks nothing` forbids digits inside
   `report-kind-scope` spans and the words `"rank"`, `"score"`, `"rating"`
   anywhere in the kinds block.

5. **The citation-in-link sweep (`:1893–1909`) applies to captions.** Any
   `"<College>'s 2024-25 Common Data Set"` text outside an `<a>` fails. A merit
   or borrowing caption must contain `sourceLink(citation)` (or render bare text
   only for a `NOT_FOUND` filing, which the test fixture does not exercise). The
   anchor count `assertEquals(6, countOf(body, "rel=\"noreferrer\""))` (:1952)
   and `CostReportProgramReturnTest.kt:320–327` (exactly three anchors) mean one
   anchor per filing, so the caption must be the ONLY place a table links it.

6. **"One table per publisher" splits the program figures.** Earnings are the
   Treasury's and debt/payment are NSLDS's (`ProgramReturnCopy.kt:285–289`),
   with different cohorts (`CostReportProgramReturnTest.kt:179–189`). Either two
   tables per subject or a publisher column; the brief must choose.

7. **A words-only National Merit row must not be `$0` and today must not carry
   `report-merit-figure`** (`:2673–2674`). A blank cell in the money column is
   the shape; the class assertion must be rewritten or the class not used on the
   blank.

8. **Facts the page drops today that a table would make reachable:** the merit
   denominators `fullTimeFreshmen` and `nonNeedMeritRecipients` (wire-only,
   `MeritAidWire.kt:55–56`); the borrowing `graduatingClassLabel` (wire-only,
   `BorrowingWire.kt:201–202`). Brief 0009 D1 is about not losing facts; adding
   these is additive and the D9 sum sweep's forbidden strings are unaffected.

9. **The merit-block hedge "not an offer to this student" is already said once
   in `sourcesSection`** (:2359–2360), and "never added together" is already
   said once (:2352–2353); moving the per-figure hedges out of the merit and
   borrowing sentences adds nothing to the kinds section that the sources
   section does not already carry, except "a grant the student never pays back",
   which is new page-authored copy if kept.

10. **No `<caption>` CSS exists for the report.** `.guide-table caption`
    (`site.css:483–487`) is scoped to `/fafsa`; a `.report-table caption` rule
    (or a promotion of the caption rule to `.data-table caption`) is a
    `site.css` change, and `site.css` is untouched on every live branch.

11. **`pipeline/rfc-258` rewrites `CollegeCostChatTool.kt`** (`payloadOf`
    extraction for a supporter-side `family_cost_picture` tool). Any 0016 slice
    that edits that file conflicts; a slice that keeps the row seam in
    `BorrowingWire.kt` / `ProgramReturnCopy.kt` / a new file does not.
