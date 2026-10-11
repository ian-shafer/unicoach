# RFC 247 — The report says it too

**Slice:** `test/09/the-report-says-it-too` (brief
`product/0010-when-do-i-test`, D30) **Status:** approved (Ian, 2026-10-10: gate
decision 1 Option B, gate decision 2 Option A)

## Summary

A parent opening the family cost report sees, for each school on the list that
publishes one, what that school pays a National Merit standing: who it pays,
whether the school pays it to everyone who holds it or only considers them, the
school's own words, its numbers where it prints them, whether it must be named
first choice, and the day we read its page. Nothing about the student is on the
page: no score, no Selection Index, no standing of hers.

RFC 243 (`test/08/the-psat-is-money`) built the corpus and put it in the chat
only, on Ian's call at that gate (brief 0010 D30). This RFC adds the second door
— the report — exactly the way RFC 239 added `report-award-grid` beside its chat
tool: a student-free read on the service, a new list on `CollegeCost`, and one
new block on the page. No table, no migration, no prompt change.

## Context

Measured in this worktree (`.scratch/ship/rfc-247/research.md` has every
citation):

- The report is server-rendered kotlinx.html in `:public-web`
  (`CostReportPage.kt`). It has no TypeScript and no iOS surface. Per school it
  prints `report-merit` → `report-award-grid` → `report-debt`
  (`CostReportPage.kt:711-713`).
- RFC 239's precedent is exact: `AutomaticAwardReader.gridAnswersInSession`
  takes college ids and no student (`AutomaticAwardService.kt:507-521`), fills
  `CollegeCost.automaticAwards`, and the page's `printableGridOf` refuses
  `NotCurated`, `NonePublished` and `Expired` before a section opens.
- **`NationalMeritService` has no student-free door.** `readInSession` reads her
  graduation date (it throws with no `students` row) and takes a `Residency`;
  the arm rule `answerFor` is private and stamps a `ResidencyScope` — a
  comparison with the family's state — on every `Read` arm
  (`NationalMeritService.kt:449-509`, `:799-825`). Passing `Residency.NotAsked`
  would put `NO_STATE_ON_FILE` on every row, a false sentence about the family.
- **A fourth refusal exists in the code.** `PublishedPackage.Withheld` — no
  cycle we still believe publishes a base rate — withholds the figures AND the
  school's words, because the words are the price
  (`NationalMeritService.kt:222-264`). The slice text lists three refusals; the
  code has four, and the code wins.
- Corpus today: 26 rows over 17 colleges — 19 automatic, 6 competitive, 1
  `none_published` (UNC). All are entry year 2027-28, read 2026-09-25, believed
  through 2027-07-31. 14 of the 25 paying rows publish words and no figure. The
  largest seeded figure is Oklahoma State's $154,500 for out-of-state students;
  the slice text's "$153,450" is Oklahoma's Fall 2026 figure, which RFC 243
  declined to seed. One publisher per college in every case.
- The page may not say "award" in our own prose
  (`CostReportPageTest.kt:685-697`); a school's quoted words are exempt
  (`:2567`). So no chat-tool copy is reused verbatim — it all says "award".

## Decisions

**D1 — school facts only, by type (inherited: RFC 239 D-E, RFC 155 D-F).** The
report door takes college ids and nothing else. Its answer type has no field
that could hold a student fact — no `ResidencyScope`, no timing arm, no standing
of hers. "A standing is not a score" lets the page print the package; it never
lets it print her result.

**D2 — every residency row, labelled by the school's audience, compared with
nothing (gate decision 2, Ian: Option A).** A school that publishes an in-state
row and an out-of-state row gets both, each labelled "for students it classes as
in-state" / "out-of-state", exactly as `report-award-grid` does. Auburn's single
in-state row prints with its label. The page does not say which row is the
family's, although the page knows the family's state: residency for tuition is a
status the school decides, and the precedent block on this same page picks
nothing.

**D3 — the base rate is said once for the page (gate decision 1, Ian: Option
B).** The share of entrants who reach Finalist is printed ONCE, in
`report-kinds` ("What kind of numbers these are"), attributed to its own
publisher (National Merit Scholarship Corporation) and the day we read it — not
inside each school's section. It is printed exactly when at least one National
Merit row prints, decided by the same function, and its value is read off the
`PublishedPackage.Priced` of the printed rows. So the contract still holds by
construction: no National Merit figure reaches the page unless the base rate it
was built with is on the same page. Ian chose this over the slice text's "rides
with any large figure" reading at the RFC 247 gate (2026-10-10); the trade is
that one school's section can show six figures with the odds said once higher up
the page.

**D4 — four refusals, per row.** A row prints only from `Automatic` or
`Competitive` with a `Priced` package. `NonePublished` (UNC), `Expired` and a
`Withheld` package print nothing, and a school whose rows all refuse gets no
section. A school we have not read is not in the answer at all (the door never
emits `NotCurated`), so the page is silent about it — never "pays nothing".

**D5 — competitive is said as considered, never as paid.** An automatic row says
the school pays it to every student holding the standing who meets its terms. A
competitive row says the school considers students holding the standing and does
not promise it. UCF's out-of-state "more than $25,000 per year" and Tulsa's
full-tuition package are the reason.

**D6 — published words first; our numbers never.** Every printed row carries the
school's own sentence, verbatim. A figure line is printed only from the school's
stored `perYearUsd` / `totalUsd` / `years`. Nothing is summed, ranked, sorted by
money or called best; schools stay in list order and rows in the read's order.

**D7 — the entry year is printed.** Each row names the entering class it was
published for (`2027-28`). The curation pass for RFC 243 found four schools'
headline figures published for the wrong year; a parent should see which year a
package is for.

**D8 — once for the page, the kind of number this is.** `report-kinds` gains,
beside the D3 base-rate sentence and printed under the same condition, one
sentence: a National Merit scholarship a school publishes is the school's own
money, paid only to students who reach the standing it names, and is not a
financial aid offer.

## Detailed Design

### 1. The answer: a scope-free arm type in `:service`

In `NationalMeritService.kt`, a new sealed type holds exactly what a school
published, with no student comparison:

```kotlin
sealed interface NationalMeritPublishedAnswer {
  val award: NationalMeritAward
  data class NonePublished(override val award, val claim: NationalMeritClaim.NonePublished)
  data class Expired(override val award, val expiredOn: LocalDate)
  data class Automatic(override val award, val claim: NationalMeritClaim.Paying, val published: PublishedPackage)
  data class Competitive(override val award, val claim: NationalMeritClaim.Paying, val published: PublishedPackage)

  companion object {
    /** THE ONE HOME of the arm rule: expiry outranks the kind; a price is built with a base rate or not at all. */
    fun of(award: NationalMeritAward, today: LocalDate, baseRate: PublishedBaseRate?): NationalMeritPublishedAnswer
  }
}
```

`of` is today's `answerFor` body minus the scope. The chat door's private
`answerFor` becomes: compute the scope, call `NationalMeritPublishedAnswer.of`,
and map each arm 1:1 onto the existing `NationalMeritSchoolAnswer` arm. The chat
tool and its wire do not change. There is one rule deciding expiry, kind and
package; the mapping is an exhaustive `when` with no `else`.

### 2. The base rate carries its citation

`PublishedBaseRate` gains `publisher: String` and `checkedOn: LocalDate`, filled
in `PublishedBaseRate.of` from the chosen cycle's `sponsor.publisher` and
`checkedOn`. The chat ignores both. The page needs them because D3 attributes
the sentence inside a block whose heading names the school.

### 3. The student-free door

```kotlin
interface NationalMeritReportReader {
  fun publishedAnswersInSession(session: SqlSession, collegeIds: Collection<CollegeId>): List<NationalMeritPublishedAnswer>
}
```

`NationalMeritService` implements it: no ids → empty list and no statement;
`NationalMeritAwardsDao.listByColleges` → empty → return; otherwise
`NationalMeritCyclesDao.list`, `PublishedBaseRate.of(cycles, today)`, and
`NationalMeritPublishedAnswer.of` per row, in the DAO's order. It reads no
`students` row and no money profile. `REPORT_READ_STATEMENTS = 2`, asserted
absolutely, like `GRID_READ_STATEMENTS`.

### 4. The cost read

`CollegeCostService` takes
`private val nationalMeritReader: NationalMeritReportReader = NationalMeritService(database)`
beside `automaticAwardService`, calls it once for `selection.selected` on the
same session, groups by college, and fills a new, non-defaulted
`CollegeCost.nationalMerit: List<NationalMeritPublishedAnswer>`. Every
`CollegeCost(...)` construction site passes it (an empty list where the caller
has no read — the ingest/loader sites and test fixtures).

### 5. The page: `report-national-merit`

In `CostReportPage.kt`, after `awardGridBlocks(cost)` and before
`debtBlock(cost)`:

```kotlin
nationalMeritBlocks(cost)
```

which renders one `nationalMeritBlock(publisher, readOn, rows)` per publisher
and read date.

`printableMeritRowOf(answer): PrintableMeritRow?` is an exhaustive `when` with
no `else`: `NonePublished`, `Expired` → null; `Automatic`/`Competitive` with
`Withheld` → null; with `Priced` → a row carrying the award, the claim, the
`Priced` package and whether it is automatic. `tierKindsSection` asks the same
function whether any row prints (D8), as it already does for grids.

One section per school whose printable rows exist (one publisher per college in
the corpus; if a school's rows ever name two publishers or two read dates, one
section per publisher and read date, so each is attributed once):

```
section.report-national-merit
  h3   "National Merit scholarships this school publishes"
  p.report-source   "Source: <publisher>, read on <UsDate>."
  ul
    li  (one per printable row, in read order)
      p.report-merit-name       (only where published) <the school's own name for its scholarship, verbatim>
      p.report-merit-standing   "<For National Merit Finalists | Semifinalists | Commended Students>
                                 entering in <2027-28>[, for students it classes as in-state|out-of-state]."
      p.report-merit-guarantee  automatic: "The school pays this to every student holding this standing who meets its terms."
                                competitive: "The school considers students holding this standing for this scholarship. It does not promise it."
      p.report-merit-figure     (only where a dollar figure is published) "<$17,500> a year" / "<$154,500> in total" / "…over <4> years";
                                a row with years and no dollar figure prints no figure line (the school's words carry it)
      p.report-merit-words      <the school's own sentence, verbatim>
      p.report-merit-first-choice  Required: "<school's words>" [+ " Name it first choice by <UsDate>."]
                                   NotRequired: "<school's words>"
                                   null: "This school's page does not say whether it must be named first choice."
      p.report-merit-apply      (only where published) "Apply by <UsDate>." + "<school's words>"
```

`tierKindsSection` prints, once, when any National Merit row prints:

```
p.report-kind  <D8 sentence>
p.report-kind.report-merit-base-rate
               "About <1.3%> of entrants in the <2028> National Merit competition reach Finalist.
                Source: <National Merit Scholarship Corporation>, read on <UsDate>."
```

The base-rate sentence reads the `Priced.baseRate` of the printed rows; every
`Priced` value in one read carries the same base rate (one
`PublishedBaseRate.of` call per read), and a page whose rows somehow carried two
different base rates is named corruption (`error`), not a choice. The percent
goes through `Share`'s spoken percent, as the chat does. The standing label is
the page's own (the page owns its copy). The audience labels mirror
`gridHeadingOf`; `IN_DISTRICT`/`UNKNOWN` are named corruption, as there. Dollar
figures go through `WholeDollars.spoken`; dates through `UsDate.of`. No anchor
is opened (the grid block's rule; the page pins one anchor kind).

Our own prose never says "award"; the school's name for its scholarship and its
sentences are quoted verbatim and may.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/testing/NationalMeritService.kt`
  — `NationalMeritPublishedAnswer` + `of`; `answerFor` delegates to it;
  `PublishedBaseRate` gains `publisher`/`checkedOn`; `NationalMeritReportReader`
  and `publishedAnswersInSession`; `REPORT_READ_STATEMENTS`.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` —
  `CollegeCost.nationalMerit`; the reader parameter; the call in
  `readInSession`.
- Any other `CollegeCost(...)` / `PublishedBaseRate(...)` construction site the
  compiler names (`college/.../CollegeScorecardLoader.kt`,
  `college/.../IngestApplication.kt`, tests).
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` — the
  block, `printableMeritRowOf`, the D8 sentence in `tierKindsSection`.
- `public-web/src/test/kotlin/ed/unicoach/web/FakeCostReportSource.kt` — a
  `nationalMerit` parameter on `costFixture`, assembled answers not rows.
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` — the page
  tests below.
- `service/src/test/kotlin/ed/unicoach/coaching/testing/NationalMeritServiceTest.kt`
  — the door's tests.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`
  — the wiring test.

## Implementation Plan

1. `NationalMeritPublishedAnswer` and `of`; re-point `answerFor`; run the
   existing `NationalMeritServiceTest` and chat-tool tests unchanged — they are
   the proof the refactor moved no chat answer.
2. `PublishedBaseRate` citation fields.
3. `NationalMeritReportReader` + `publishedAnswersInSession` + statement count.
4. `CollegeCost.nationalMerit` and the cost read; fix construction sites.
5. The page block, the kinds sentence, the fixtures, the tests.
6. `nix develop -c bin/test` (full, no module, no `-f`).

## Tests

Service (`NationalMeritServiceTest`, real DB, `NationalMeritFixture`):

- the report door answers `Automatic`, `Competitive`, `NonePublished` and
  `Expired` for seeded rows, and `Withheld` packages when no believed cycle
  publishes a funnel;
- it needs no student: it answers for college ids with no `students` row;
- a college with no rows contributes nothing (no `NotCurated` arm exists);
- statements: 2 for one college and for ten; 0 for none.

Wiring (`CollegeCostServiceTest`): a seeded row for a school on the list reaches
`CollegeCost.nationalMerit`; a listed school with no row gets an empty list.

Page (`CostReportPageTest`, real route over the fake source):

- an automatic priced row prints the block, the heading, the standing with entry
  year and audience, the guarantee sentence, the figure, the school's words, the
  first-choice line with its `UsDate` and the source line, and NO base-rate
  sentence inside the block;
- a competitive row says it is considered and not promised, and never prints the
  automatic sentence;
- a words-only row prints the school's sentence and no figure line, never `$0`;
- `NonePublished`, `Expired`, `Withheld` and an empty list each print no
  `report-national-merit` and no D8 sentence; one expired row beside a live row
  prints only the live row;
- an in-state and an out-of-state row both print, each labelled, and the block
  never names the family's state;
- a row whose residency basis is `in_district` or `unknown` refuses the page (a
  server error) rather than print a row with no audience. The two-base-rate
  `error` has no test: one read calls `PublishedBaseRate.of` once, so every
  `Priced` on a page carries the same value by construction, and no page test
  can reach the guard without building a state the read cannot produce;
- a row with years and no dollar figure prints no figure line;
- order: `report-merit` < `report-award-grid` < `report-national-merit` <
  `report-debt`;
- no student fact: no "Selection Index", "your student", "scored", "PSAT score"
  or "qualifies";
- the D8 sentence and the base-rate sentence (with its own source and read date)
  print once for a two-school page, inside `report-kinds`, and are absent when
  no National Merit row prints;
- our own prose never says "award"; a school's quoted words that do are the only
  occurrences.
