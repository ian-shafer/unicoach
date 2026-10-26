# Page inventory: today's Family Cost Report, block by block, and a table-first mock of each block

Research report for brief 0016 (`glance`). Measured 2026-10-25 against `main`
(renderer `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`,
2,403 lines). Every number below was computed by a script over real renders; the
scripts and their outputs are named in METHOD. Fixture data throughout: school
names, repeated identical figures ("$12,000", "394 of the 1000") and one school
citing another school's scholarship office are fixture artefacts, not page
defects.

## METHOD

### Renders used

98 HTML dumps of the page's own tests (`CostReportPageTest`, one file per
render) at
`/private/tmp/claude-501/-Users-ian-Work-unicoach/a955ab81-8f09-4868-acc1-50bb8f0be61e/scratchpad/dumps/`.
Grouped by content hash they are 62 distinct renders. The three used for the
block-by-block tables:

| Render (file prefix)      | Visible words | Schools | Blocks present                                                                                   | Why this one                                                                    |
| ------------------------- | ------------: | ------: | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------- |
| `1a8a9fe6` (×7 identical) |         1,404 |       3 | basis, summary, 3× school, 3× merit, 3× debt (Scorecard + borrowing), kinds, sources             | The "verbose" fixture: merit + borrowing at every school. The brief's baseline. |
| `d10c4ed2`                |         1,314 |       3 | basis, summary, 3× school, 3× merit, 2× national-merit, 3× debt (Scorecard only), kinds, sources | Richest render carrying `report-national-merit`.                                |
| `a6daa29d`                |         1,223 |       3 | basis, summary, 3× school, 3× merit, 2× award-grid, 3× debt (Scorecard only), kinds, sources     | Richest render carrying `report-award-grid`.                                    |

**Gap:** no dump carries `report-program-return`. Those renders come from a
second test class, `CostReportProgramReturnTest`, which was not dumped. The
program-return block below is reconstructed from that test's `everyFigureArm()`
fixture and `ProgramReturnCopy`'s sentence builders (every string is quoted from
source, and the test file asserts the exact sentences, so the reconstruction is
exact for the words; it was not rendered). Nothing was run in the root checkout.

The 1,403 in the brief vs 1,404 here: the brief's figure came from `measure.py`,
which joins text nodes without a separator, so `…Common Data
Set</a>.` is one
token "Set.". The page test's `proseOf` replaces every tag with a space, making
"Set" and "." two tokens. Every count in this report uses the test's rule
(`visibleWordsOf` = `proseOf(reportSectionOf(body))` split on whitespace), so it
agrees with what `[MEASURE]` prints: **1,404**.

### Scripts (all under the scratch dir)

- `blocks.py <dump>` — walks `<section class="report">`; every
  `section`/`div`/`aside` whose class starts with `report-` is a block instance.
  Per instance: words (test rule), words inside any `<table>`, number of
  `<p>`+`<li>`, currency figures in prose (regex `\$\d[\d,]*(?:\.\d+)?`, outside
  tables), and `<p>`/`<li>` with ≥3 currency figures. `-v` prints each
  paragraph.
- `frames.py <dump>` — repeated-frame words: per per-school block, prose words
  minus figure tokens (any token containing a digit or `$`) minus the school's
  own name tokens; "repeated" = that frame × (instances − 1).
- `mocks.py` — the mock tables as strings, counted with the same rule, and the
  after-arithmetic in §5.
- `inv.py` — groups dumps by hash and lists block kinds (used to pick renders).

### Caveats

- Fixture data. `394 of the 1000` and `$12,000` repeat because
  `borrowingFixture`/`meritFixture` default them; `Ashford College`'s National
  Merit block cites "Riverside State University Office of Scholarships" because
  the fixture reuses one publisher string.
- "Words in `<table>`" counts everything inside `<table>…</table>`, so a
  `<caption>` would count as table words. Today no table has a caption.
- Words per block are the block's own text; a `report-merit` nested inside a
  `report-school` is counted once, under `report-merit`.

## Today's page, block by block

### Three-school verbose render (`1a8a9fe6`, 1,404 words)

| Block                               | School                     |     Words | In `<table>` |     Prose | `<p>`+`<li>` | `$` in prose | R2 viol. (≥3 `$`) | Repeated-frame words |
| ----------------------------------- | -------------------------- | --------: | -----------: | --------: | -----------: | -----------: | ----------------: | -------------------: |
| (h1 + live line)                    | —                          |        18 |            0 |        18 |            1 |            0 |                 0 |                    — |
| report-basis                        | —                          |       283 |            0 |       283 |            9 |            2 |                 0 |                    — |
| report-summary                      | —                          |       125 |           43 |        82 |            2 |            0 |                 0 |                    — |
| report-school (intro + price table) | Lakeshore State University |       119 |           57 |        62 |            3 |            0 |                 0 |            — (first) |
| report-merit                        | Lakeshore State University |        54 |            0 |        54 |            3 |            1 |                 0 |            — (first) |
| report-debt                         | Lakeshore State University |       112 |            0 |       112 |            6 |            3 |                 0 |            — (first) |
| report-school                       | Eastbrook University       |       102 |           57 |        45 |            3 |            0 |                 0 |                   41 |
| report-merit                        | Eastbrook University       |        53 |            0 |        53 |            3 |            1 |                 0 |                   48 |
| report-debt                         | Eastbrook University       |       111 |            0 |       111 |            6 |            3 |                 0 |                   99 |
| report-school                       | Wynnford College           |       102 |           57 |        45 |            3 |            0 |                 0 |                   41 |
| report-merit                        | Wynnford College           |        53 |            0 |        53 |            3 |            1 |                 0 |                   48 |
| report-debt                         | Wynnford College           |       111 |            0 |       111 |            6 |            3 |                 0 |                   98 |
| report-kinds                        | —                          |        41 |            0 |        41 |            1 |            0 |                 0 |                    — |
| report-sources                      | —                          |       120 |            0 |       120 |            3 |            0 |                 0 |                    — |
| **Total**                           |                            | **1,404** |      **214** | **1,190** |           52 |           14 |             **0** |              **375** |

Prose share today: 1,190 / 1,404 = **84.8%**. Per school (intro + price table

- merit + debt): 285 / 266 / 266 words, of which 57 are the price table.

Repeated frame: 375 of the 1,404 words (26.7%) are a sentence frame the parent
has already read at the previous school. The debt block alone re-reads 197 words
across schools 2 and 3 (its 6 sentences are 99 words of frame around 10 figure
tokens).

R2 today: **zero** `<p>`/`<li>` in this render carries three or more currency
figures. The debt block's six paragraphs carry 1, 0, 1, 0, 1, 0.

### Richest National Merit render (`d10c4ed2`, 1,314 words)

| Block                 | School                     |     Words | In `<table>` |     Prose | `<p>`+`<li>` | `$` in prose |     R2 viol. | Repeated-frame words |
| --------------------- | -------------------------- | --------: | -----------: | --------: | -----------: | -----------: | -----------: | -------------------: |
| (h1 + live line)      | —                          |        18 |            0 |        18 |            1 |            0 |            0 |                    — |
| report-basis          | —                          |       277 |            0 |       277 |            9 |            2 |            0 |                    — |
| report-summary        | —                          |       128 |           40 |        88 |            2 |            0 |            0 |                    — |
| report-school         | Riverside State University |        78 |           25 |        53 |            3 |            0 |            0 |                    — |
| report-merit          | Riverside State University |        54 |            0 |        54 |            3 |            1 |            0 |                    — |
| report-national-merit | Riverside State University |        71 |            0 |        71 |            6 |            3 | 0 (see note) |                    — |
| report-debt           | Riverside State University |        29 |            0 |        29 |            1 |            1 |            0 |                    — |
| report-school         | Ashford College            |        76 |           25 |        51 |            3 |            0 |            0 |                   47 |
| report-merit          | Ashford College            |        53 |            0 |        53 |            3 |            1 |            0 |                   48 |
| report-national-merit | Ashford College            |        71 |            0 |        71 |            6 |            3 | 0 (see note) |                   62 |
| report-debt           | Ashford College            |        29 |            0 |        29 |            1 |            1 |            0 |                   27 |
| report-school         | Riverside State University |       136 |           74 |        62 |            3 |            0 |            0 |                   47 |
| report-merit          | Riverside State University |        54 |            0 |        54 |            3 |            1 |            0 |                   48 |
| report-debt           | Riverside State University |        29 |            0 |        29 |            1 |            1 |            0 |                   28 |
| report-kinds          | —                          |        91 |            0 |        91 |            3 |            0 |            0 |                    — |
| report-sources        | —                          |       120 |            0 |       120 |            3 |            0 |            0 |                    — |
| **Total**             |                            | **1,314** |      **164** | **1,150** |           51 |           14 |        **0** |              **307** |

Prose share: 1,150 / 1,314 = **87.5%**.

Note on the National Merit `<li>`: its five `<p>` children carry 0, 0, 2, 1, 0
currency figures, so no paragraph breaks R2. But the `<li>` _wrapping_ them
contains `$17,500 … $70,000 … $17,500` = 3. A test that counts `$` per `<li>`
without removing nested block children reports it as a violation: 9 such `<li>`
across the 98 dumps, and nothing else. See §6 for the fix in the test and §7 for
the finding.

### Richest award-grid render (`a6daa29d`, 1,223 words)

Only the rows that differ from the National Merit render:

| Block             | School                     |     Words | In `<table>` |     Prose | `<p>`+`<li>` | `$` in prose | R2 viol. | Repeated-frame words |
| ----------------- | -------------------------- | --------: | -----------: | --------: | -----------: | -----------: | -------: | -------------------: |
| report-award-grid | Riverside State University |        43 |            0 |        43 |            4 |            1 |        0 |                    — |
| report-award-grid | Ashford College            |        43 |            0 |        43 |            4 |            1 |        0 |                   37 |
| report-kinds      | —                          |        56 |            0 |        56 |            2 |            0 |        0 |                    — |
| **Total**         |                            | **1,223** |      **164** | **1,059** |           46 |           10 |    **0** |                      |

Prose share: 1,059 / 1,223 = **86.6%**. The grid in this fixture has one row;
each extra published row costs 8 words today ("Presidential — SAT 1200–1250 —
$6,000 a year").

### Program-return block (reconstructed, not rendered)

From `CostReportProgramReturnTest.everyFigureArm()`: Lakeshore runs Biology
(three programs) and Chemistry (one); Eastbrook runs neither; Wynnford has
Biology with every figure withheld.

| School    | Element                                            |   Words | `$` | R2 viol. |
| --------- | -------------------------------------------------- | ------: | --: | -------: |
| Lakeshore | `p.report-program-subject` (Biology lead)          |       7 |   0 |        0 |
| Lakeshore | `p.report-program` Biology, General                |      90 |   3 |    **1** |
| Lakeshore | `p.report-program` Ecology, Evolution, Systematics |      46 |   0 |        0 |
| Lakeshore | `p.report-program` Biochemistry                    |      14 |   0 |        0 |
| Lakeshore | `p.report-program-subject` (Chemistry lead)        |       7 |   0 |        0 |
| Lakeshore | `p.report-program` Chemistry                       |      68 |   1 |        0 |
| Lakeshore | **subtotal**                                       | **232** |   4 |        1 |
| Eastbrook | 2× `p.report-program-subject` (no program)         |      28 |   0 |        0 |
| Wynnford  | subject mark (all withheld) + no program           |      41 |   0 |        0 |

This is the **only R2 violation on today's page**: a program with all three
figures prints them in one `<p>` (`programParagraph` joins `programSentencesOf`
into one paragraph). Every program with all three figures reported is a
violation; the corpus will have many.

## Facts per block

The information-loss checklist. Each item is a fact today's page states;
sentences are quoted verbatim from the renders or from the copy seam that builds
them. "Variant" = a sentence the renderer prints under other data.

### Merit block (`report-merit`, CDS, school's own)

Rendered only when `cost.meritAid` has a share or an average.

1. Heading: "Money this school gives for something other than financial need".
2. Publisher and cycle, as a link: "Source: " + `citedAs` ("Lakeshore State
   University's 2024-25 Common Data Set") + "." — `href` = the school's own
   file, `rel="noreferrer"`; the same words with no anchor when the file is
   recorded as gone (RFC 200).
3. Share: "25% of all full-time freshmen received non-need (merit) aid."
   (`MeritAidWire.shareLabel`; absent when the share is null).
4. Average: "the average non-need (merit) aid was $12,000 - a grant the student
   never pays back, reported for last year's class, not an offer to this
   student." (`MeritAidWire.averageLabel`; absent when null). Three hedges in
   one sentence: grant not loan; cohort = last year's class; not an offer.
5. Implicit: the block is absent, not blank, when the school has no filing.
6. Not stated anywhere on the page: the assurance tier of these figures
   (voluntary self-report). `tierKindsSection` covers prose figures = federal
   debt and program figures only. Not a regression risk, but D1-relevant: the
   new page should not make it _less_ reachable, and the link in (2) is what the
   frozen "We link to the college's own file" sentence would rely on if the tier
   were ever spoken here.

### Borrowing block (inside `report-debt`, CDS, school's own)

Rendered only for `BorrowingCoverage.Reported`; absent for NoFiling / NoBlock /
NotReadByUs.

1. Header, link + tail (`BorrowingWire.blockHeader`): "[Lakeshore State
   University's 2024-25 Common Data Set] reports these figures for the students
   who graduated from it that year -- not this year's freshmen, and not all its
   undergraduates." Facts: publisher, cycle, cohort (that year's graduates), two
   negative hedges.
2. Per loan type, in `LoanType.SPOKEN_ORDER` (any / federal / institutional /
   state / private), average: "Graduates who borrowed federal loans owed $20,747
   on average by the time they graduated." Spoken loan types: "a loan of any
   kind", "federal loans", "a loan from the school itself", "a state loan", "a
   private loan".
3. Per loan type, share with its numerator and denominator: "394 of the 1000
   students who graduated borrowed federal loans -- 39.4%." Facts: borrowers,
   graduating class (unformatted integer today), share to one decimal. Absent
   when `BorrowerCounts.ContradictsGraduatingClass`.
4. Variant, our gap: "This filing answers what its graduates borrowed in federal
   loans, and WE could not read that answer out of it -- say that the figure is
   missing from our data, never that this school does not report it."
   (`notReadByUsLabel`; coach-voiced copy rendered as-is on the page).
5. Variant, publisher's gap: "This filing answers what its graduates borrowed in
   a private loan and no figure is shown here: {FigureStatusCopy agentless
   statement} Say it that way, never that this school does not report it."
6. Variant, denominator missing: "This filing reports the size of its graduating
   class and WE could not read that answer out of it, so no share of graduates
   who borrowed can be given here -- say that the figure is missing from our
   data, never that this school does not report it."
7. Variant, private unfiled: "This filing reports no average for private loans,
   so these figures are not the whole of what its graduates borrowed."
8. Each sentence ends "." added by the page.

### Federal debt (first `<p>` of `report-debt`, Scorecard)

1. Shown: "The U.S. Department of Education College Scorecard reports that
   students who finished here carried a median of $21,000 in federal loans. The
   source publishes no year for this figure." Facts: publisher (read off the
   cell's `MoneySource` through `MoneySourceCopy.openingLabelOf`, never typed),
   figure, "federal loans", "no year".
2. Variant, no note resolved: the same figure sentence with no publisher
   ("Students who finished here carried …") — documented as unreachable.
3. Variant, blank (`blankFor`): "This school does not report the federal loan
   debt students carried when they finished." for NOT_REPORTED / REPORTED
   without a figure; otherwise the status statement + the tier sentence, e.g.
   "The U.S. Department of Education College Scorecard withholds this figure to
   protect students' privacy. The college had to report this to the federal
   government, and the form checks it against last year's answer. Nobody audits
   it." (seen in dumps), or "We have not collected this figure yet. …", or "We
   show this school's prices for 2023-24, and the most recent year we hold this
   figure for is 2021-22. We do not mix years inside one total, so it is not
   shown here. …".
4. Its kind, once per page in `report-kinds`: "The median federal loan debt:
   This comes from federal loan and tax records, not from the college. It covers
   only students who got federal aid, and small numbers are blurred a little to
   protect privacy." (scope sentence + byte-frozen ADMINISTRATIVE_RECORD
   sentence; the scope names the school when tiers differ: "The median federal
   loan debt at Lakeshore State University:").

### National Merit row (`report-national-merit`, school's scholarship page)

One section per (publisher, read date); one `<li>` per printable row.

1. Heading: "National Merit scholarships this school publishes".
2. Source, no link: "Source: Riverside State University Office of Scholarships,
   read on Sep 25, 2026."
3. Optional award name: "National Merit Finalist Award" (`report-merit-name`).
4. Standing + entering class + optional audience: "For National Merit Finalists
   entering in 2027-28." / "…, for students it classes as in-state." / "…
   out-of-state." Standings: Finalists / Semifinalists / Commended Students.
5. Guarantee: "The school pays this to every student holding this standing who
   meets its terms." (automatic) or "The school considers students holding this
   standing for this scholarship. It does not promise it." (competitive).
6. Figure, when priced: "$17,500 a year, $70,000 in total over 4 years."
   (per-year and/or total, optional years; absent when the school publishes
   words only — never "$0").
7. School's words, always: "Finalists receive $17,500 a year for four years." /
   "Finalists receive the full cost of tuition and required fees, for four
   years." / "Finalists will be awarded full tuition for four years."
8. First choice: "Name us your first choice with NMSC. Name it first choice by
   May 1, 2027." (Required with deadline) / the school's words alone (Required
   without deadline, or NotRequired) / "This school's page does not say whether
   it must be named first choice." (unsaid).
9. Optional apply deadline: "Apply by {date}. {school's words}".
10. Once per page in `report-kinds`: "A National Merit scholarship a school
    publishes is the school's own money, paid only to students who reach the
    standing it names, and is not a financial aid offer." and the base rate with
    its own citation: "About 1.3% of entrants in the 2028 National Merit
    competition reach Finalist. Source: Example Merit Corporation, read on Sep
    1, 2026."
11. Implicit: Expired, NonePublished and Withheld-package rows are not printed
    at all.

### Award grid (`report-award-grid`, school's admissions page)

One section per published grid (a school may publish two, by residency).

1. Heading: "Automatic scholarships this school publishes" + optional " for
   students it classes as in-state" / "out-of-state".
2. Source, no link: "Source: Riverside State University Office of Admissions,
   read on Aug 1, 2026."
3. When a score does not move the money: "A test score does not change what this
   school's automatic scholarships pay."
4. Score requirement, the school's words or the silence: "A test score is
   required for scholarship consideration." / "This school does not say whether
   a test score must be submitted."
5. Optional score deadline: "Scores must be with this school by Dec 4, 2026."
6. One `<li>` per band, in the school's published order: "{name} — {asks} —
   {pays}", asks = score range and/or GPA, pays = "$6,000 a year" or the
   school's words. Seen: "Presidential — SAT 1200–1250 — $6,000 a year",
   "Presidential Elite — SAT 1500–1600 — tuition and fees", "Achievement — no
   test score, GPA 3.50 and above — $2,000 a year", "Trustee — GPA 3.75 and
   above — $4,000 a year", "Academic Achievement Award — SAT 1200–1250 — The
   award amount varies from $8,000- $12,000 annually". GPA forms: "GPA
   3.50–3.74", "GPA 3.50 and above", "GPA up to 3.49". A band with no condition:
   "{name} — {pays}".
7. Once per page in `report-kinds`: "An automatic scholarship is published by
   the school and is not a financial aid offer."
8. Implicit: NotCurated / NonePublished / Expired grids are not printed.

### Program returns (`report-program-return`, Scorecard field-of-study files)

Inside `report-debt`, between the Scorecard sentence and the borrowing header.

1. Subject lead: "Biology, which your student wants to study:".
2. Subject mark instead of programs: "This school does not run that program." /
   "Every matching program's figures are withheld by the U.S. Treasury and the
   federal student loan system to protect students' privacy."
3. Per program, title with the publisher's trailing period dropped: "Biology,
   General:".
4. Earnings: "Graduates working four years after finishing earned a median of
   $41,200 a year, according to the U.S. Treasury (students who finished in
   2017-18 and 2018-19, in 2024 dollars)." Facts: figure, unit (a year),
   publisher, cohort years, inflation base year.
5. Debt: "Graduates who borrowed federal loans finished with a median federal
   loan debt of $26,000, according to the federal student loan system (students
   who finished in 2018-19 and 2019-20)."
6. Payment: "For graduates who borrowed federal loans, the median monthly
   payment on a standard 10-year plan is $291, by the federal student loan
   system's own estimate (students who finished in 2018-19 and 2019-20)."
7. Imputed variant: the valued sentence + "The U.S. Treasury estimated this
   figure; the school did not report it."
8. Gap marks: "Median earnings: withheld by the U.S. Treasury to protect
   students' privacy." / "Median federal loan debt: This school did not report
   this figure." / "Monthly payment on a standard 10-year plan: We hold no
   published figure for that measure of that program at this school." / "All of
   this program's figures are withheld by … to protect students' privacy." /
   "Biochemistry: This school runs that program and we hold no published figures
   for it."
9. Once per page in `report-kinds`: scope "The median federal loan debt, the
   median earnings by field of study, the median federal loan debt by field of
   study and the monthly loan payment by field of study:" + the frozen
   ADMINISTRATIVE_RECORD sentence; and "Figures by field of study come from the
   graduates of each program, and the publishers withhold a program's figure
   when too few of its graduates received federal aid to keep them anonymous."
10. Once per page in `report-sources`: "The figures by field of study come from
    the records of the U.S. Treasury and the federal student loan system, and
    you can look up any program in the [College Scorecard]. They describe past
    graduates of each program who received federal aid, not this family, and
    they are never added together and never compared with each other or with any
    other figure here."

### Price table (`table.report-table` in `report-school`) and its intro lines

1. `h2` school name; `p.report-school-where` "Springfield, CA".
2. Residency line: "Lakeshore State University is a public school, and the
   tuition and fees figure that applies to this family is its in-state one." /
   "… its out-of-state one." / "Eastbrook University publishes one price for
   everyone, so where the family lives changes nothing there." / "UC San Diego
   is a public school that publishes two tuition and fees figures, and the state
   the family lives in is not on file, so neither figure can be shown as
   theirs."
3. Tier line: "This school publishes one tuition price in what we hold; where
   that price is for one kind of student, the tuition figure beside it says
   which." / "For this school our source does not separate a district price from
   the state price, so the in-state figure is shown as it was published and we
   cannot say whether a lower district price exists."
4. Header row: corner "Per year"; one column per published way of living
   ("living on campus", "renting off campus", "living at home"); a school with
   no parts prints "Per year | Per year".
5. Rows: "Tuition and fees" (spanning), "Housing and food", "Books and supplies"
   (spanning), "Other expenses", "Total per year", "The published price"
   (spanning), "The likely price after a financial aid offer" (spanning).
6. Cell notes (`span.report-note`): "our assumption, not a figure this school
   published" beside the at-home "$0"; "an overall average across all families,
   not a figure for this family" / "the average for families with a household
   income of $48,001 to $75,000" beside the net price.
7. Blanks (`span.report-blank`): "Not reported by this school"; "No total — a
   part of this price is missing"; "Not shown — the state the family lives in is
   not on file"; "Not shown — this school publishes this figure for in-state
   students. Your family would pay the out-of-state price — the totals above.";
   the publisher-status blanks with tier sentence (as for debt).
8. The `data-label` on every value cell = its column header, which the 640px
   stylesheet prints before the value.

### Summary table (`report-summary`)

1. Heading "The schools side by side".
2. Hint before: "Every tuition and fees figure in this table is for living on
   campus, which is the one way of living every school here is priced for." /
   "No one way of living is priced at every school here, so this table compares
   only each school's own school-wide figures; the parts of the price are quoted
   school by school below."
3. Columns: School, [Way of living], Tuition and fees, The published price, The
   likely price after a financial aid offer. **No unit or period in any header**
   ("per year" appears nowhere in this table).
4. One row per school; blanks as in the price table.
5. Hint after: "The published price is each school's own published cost of
   attendance, an average blended across the ways of living, so it is not the
   sum of the parts quoted below. At a public school it, and the likely price
   after a financial aid offer, are figures for students paying in-state
   tuition."

## Table-first mocks

Conventions: `<caption>` carries publisher + cohort + the link (link text =
`citedAs`, exactly today's anchor). Units in headers. No total row. Phone form =
the existing 640px rule: `thead` hidden, each `<tr>` a card, each cell prefixed
by its `data-label` + ": ". Word counts are by the test's rule and include the
caption as table words.

### Merit (CDS) — 36 words (today 54); 26 of them in the table

```
### Money this school gives for something other than financial need

| Measure                                                 | Figure  |
|---------------------------------------------------------|---------|
| Full-time freshmen who received non-need (merit) aid    | 25%     |
| Average non-need (merit) aid                            | $12,000 |

Caption: [Lakeshore State University's 2024-25 Common Data Set], last year's full-time freshmen
```

Where each fact went:

| Fact (from §3)                                 | Now                                                                                                                                        |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| Heading                                        | `h3`, unchanged                                                                                                                            |
| Publisher + cycle + link                       | caption (link text = `citedAs`, same `href`/`rel`)                                                                                         |
| "25% of all full-time freshmen received…"      | row 1 header + cell                                                                                                                        |
| "the average non-need (merit) aid was $12,000" | row 2 header + cell                                                                                                                        |
| "a grant the student never pays back"          | `report-kinds`, once: "Non-need (merit) aid is a grant the student never pays back." (11 words)                                            |
| "reported for last year's class"               | caption: "last year's full-time freshmen"                                                                                                  |
| "not an offer to this student"                 | dropped as restatement — `report-sources` already says "They are not an offer, and no figure here is a price this family has been quoted." |
| Block absent when no filing                    | unchanged                                                                                                                                  |

Stacked card reads: "Measure: Full-time freshmen who received non-need (merit)
aid / Figure: 25%" — the row header should be the `<th scope=row>` and carry no
`data-label`, so it reads "Full-time freshmen who received non-need (merit) aid:
25%". Reads well. Note the share's `$`-free cell and the average's cell carry
different units; with two rows that is tolerable, and the alternative (two
columns, one row: "Received merit aid | Average") stacks as "Received merit aid:
25% / Average: $12,000", 2 words shorter. Either form.

### Borrowing (CDS) — 40 words (today 75: header 24 + four sentences 51)

```
| Loan type       | Borrowed, of the 1,000 who graduated | Average owed at graduation |
|-----------------|--------------------------------------|----------------------------|
| Federal loans   | 394 (39.4%)                          | $20,747                    |
| A private loan  | 83 (8.3%)                            | $43,865                    |

Caption: [Lakeshore State University's 2024-25 Common Data Set]: what the students who graduated from it that year borrowed
```

| Fact                                                                             | Now                                                                                                                                                                                             |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Publisher, cycle, link                                                           | caption                                                                                                                                                                                         |
| Cohort: "the students who graduated from it that year"                           | caption                                                                                                                                                                                         |
| "not this year's freshmen, and not all its undergraduates"                       | `report-kinds`, once: "A school's borrowing figures are for the students who graduated from it that year — not this year's freshmen, and not all its undergraduates." (24 words, replaces 3×12) |
| Loan type, spoken ("federal loans", "a private loan", …)                         | row header, same words                                                                                                                                                                          |
| Average owed, "by the time they graduated"                                       | column 3, unit and moment in the header once (R3)                                                                                                                                               |
| Borrowers 394, graduating class 1000, share 39.4%                                | column 2 cell + header; the denominator said once in the header instead of once per loan type. Formatting change: "1,000" where today prints "1000".                                            |
| Each loan type separate, never summed                                            | no total row; the kinds/sources sentence already says it                                                                                                                                        |
| Variant sentences (not read by us / withheld / class not read / private unfiled) | carry no figure; stay as one `<p>` each after the table, or as a spanning row ("A private loan                                                                                                  |

Stacked card: "Loan type: Federal loans / Borrowed, of the 1,000 who graduated:
394 (39.4%) / Average owed at graduation: $20,747". Reads well; the long second
label is the price of putting the denominator in the header. "Loan type: A
private loan" reads slightly oddly with the article; "Private loan" would need
`LoanType.spoken` to change, which the coach shares — leave it.

### Federal debt (Scorecard) — 21 words as a sentence (today 29), or 20 as a one-row table

Recommended: keep the sentence, trimmed:

```
The U.S. Department of Education College Scorecard reports that students who finished here carried a median of $21,000 in federal loans.
```

with "The source publishes no year for this figure." moved to `report-kinds`
once: "The College Scorecard publishes no year for its median federal loan debt
figure." (13 words, replaces 3×8).

If the spec wants every figure in a cell, the one-row form:

```
| Median federal loan debt of students who finished here |
|--------------------------------------------------------|
| $21,000                                                |

Caption: U.S. Department of Education College Scorecard (no year published)
```

= 20 words, one `<table>` of one cell; stacked it reads "Median federal loan
debt of students who finished here: $21,000". It must be its own `<table>` with
its own caption — never a row in the price table (whose "Total per year" row
would read as including it) and never in the borrowing table (constraint 1).
Blank variants stay sentences in either form: they carry no figure and end with
a byte-frozen tier sentence.

| Fact                                       | Now                       |
| ------------------------------------------ | ------------------------- |
| Publisher (from the cell's `MoneySource`)  | sentence / caption        |
| "$21,000", "median", "federal loans"       | sentence / cell + header  |
| "finished here" (completers, not entrants) | sentence / header         |
| "The source publishes no year"             | `report-kinds` once       |
| Tier sentence                              | `report-kinds`, unchanged |
| Blank variants                             | unchanged `<p>`           |

### National Merit (school's page) — 64 words as a table (today 71); 56 as a definition list

Table form:

```
### National Merit scholarships this school publishes

| For                                        | Paid? | A year  | In total            | First choice                                                              | In the school's words                              |
|--------------------------------------------|-------|---------|---------------------|---------------------------------------------------------------------------|----------------------------------------------------|
| National Merit Finalists entering in 2027-28 | Paid | $17,500 | $70,000 over 4 years | Name us your first choice with NMSC. Name it first choice by May 1, 2027. | Finalists receive $17,500 a year for four years. |

Caption: Riverside State University Office of Scholarships, read on Sep 25, 2026
```

| Fact                                                 | Now                                                                                                                                                                                                                                                                    |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Heading, source + read date                          | `h3`, caption                                                                                                                                                                                                                                                          |
| Award name (optional)                                | prefix of the "For" cell, or its own first column when any row has one                                                                                                                                                                                                 |
| Standing, entering class, audience                   | "For" cell, same sentence minus "For"                                                                                                                                                                                                                                  |
| Guarantee (automatic / competitive)                  | "Paid?" cell = "Paid" / "Considered"; the two 14- and 15-word sentences move to `report-kinds` once: "A National Merit row marked paid is paid to every student holding that standing who meets the school's terms; one marked considered is not promised." (25 words) |
| "$17,500 a year", "$70,000 in total", "over 4 years" | two cells, units in headers; years stay in the total cell (it qualifies the total)                                                                                                                                                                                     |
| School's words                                       | last cell, verbatim                                                                                                                                                                                                                                                    |
| First-choice rule + deadline / unsaid                | cell, verbatim (the "unsaid" sentence is 12 words in a cell)                                                                                                                                                                                                           |
| Apply deadline (optional)                            | a seventh column when any row has one                                                                                                                                                                                                                                  |
| Kind sentence + base rate                            | `report-kinds`, unchanged                                                                                                                                                                                                                                              |

Stacked card: "For: National Merit Finalists entering in 2027-28 / Paid?: Paid /
A year: $17,500 / In total: $70,000 over 4 years / First choice: Name us your
first choice with NMSC. Name it first choice by May 1, 2027. / In the school's
words: Finalists receive $17,500 a year for four years." Reads well stacked.
**Reads badly as a grid**: six columns, two of which hold sentences, at desktop
width; a school publishing words only leaves two empty money cells ("A year: "
with nothing after it on the card unless `td:empty` is hidden).

Definition-list form (recommended, §7): the same five lines as today's `<li>`,
each `<dt>`/`<dd>` pair so the label is in the markup rather than in the
sentence, guarantee as one word: 56 words. Zero `<table>` words, so it does not
move the prose share, but it removes the 14-word repeated guarantee and makes
the phone and desktop readings identical.

### Award grid (school's page) — 46 words for a one-row grid (today 43); 5 words per further row (today 8)

```
### Automatic scholarships this school publishes

| Scholarship  | Test score    | GPA | Pays a year |
|--------------|---------------|-----|-------------|
| Presidential | SAT 1200–1250 | —   | $6,000      |

Caption: Riverside State University Office of Admissions, read on Aug 1, 2026. A test score is required for scholarship consideration. Scores must be with this school by Dec 4, 2026.
```

| Fact                                                                                                                 | Now                                                             |
| -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| Heading incl. residency audience                                                                                     | `h3`, unchanged                                                 |
| Publisher + read date                                                                                                | caption                                                         |
| Score requirement (school's words / "does not say")                                                                  | caption, verbatim                                               |
| Score deadline                                                                                                       | caption, verbatim                                               |
| "A test score does not change what this school's automatic scholarships pay."                                        | caption, when it applies; the Test score column is then omitted |
| Band name                                                                                                            | column 1                                                        |
| Score range / "no test score" row label                                                                              | column 2                                                        |
| GPA bound(s)                                                                                                         | column 3 (omit the column when no band has one)                 |
| Pays: "$6,000 a year" / school's words ("tuition and fees", "The award amount varies from $8,000- $12,000 annually") | column 4, unit in the header; words cells verbatim              |
| Published order                                                                                                      | row order, unchanged                                            |
| Kind sentence                                                                                                        | `report-kinds`, unchanged                                       |

Stacked card: "Scholarship: Presidential / Test score: SAT 1200–1250 / Pays a
year: $6,000" — with the empty GPA cell either omitted from the markup or hidden
(`[data-label]:empty { display: none }`), otherwise the card prints "GPA: " with
nothing after it. A words cell reads "Pays a year: tuition and fees" —
acceptable; "Pays a year: The award amount varies from $8,000- $12,000 annually"
reads as the school's sentence, which it is. Flag: a band with no condition at
all ("{name} — {pays}") stacks as two lines and is fine.

### Program returns (Scorecard field-of-study files) — 216 words for Lakeshore (today 232), zero R2 violations (today 1)

One table per subject per school:

```
| Program                         | Median earnings a year, four years after finishing | Median federal loan debt at finishing | Median monthly payment on a standard 10-year plan |
|---------------------------------|----------------------------------------------------|---------------------------------------|---------------------------------------------------|
| Biology, General                | $41,200                                            | $26,000                               | $291                                              |
| Ecology, Evolution, Systematics | Withheld by the U.S. Treasury to protect students' privacy | This school did not report this figure | We hold no published figure for that measure of that program at this school |
| Biochemistry                    | This school runs that program and we hold no published figures for it (spanning)                                                                                   |

Caption: Biology programs at Lakeshore State University, for graduates who received federal aid. Earnings: the U.S. Treasury, students who finished in 2017-18 and 2018-19, in 2024 dollars. Federal loan debt and monthly payment: the federal student loan system, students who finished in 2018-19 and 2019-20.
```

Biology table 119 words (caption 44, header 23, rows 5 + 33 + 14); Chemistry
table 97 (caption 44, header 23, row 30). The caption is 44 words because it
carries two publishers and two cohorts. Those cohorts are properties of the
Scorecard release, identical for every program and school on the page, so the
page can say them **once** in `report-kinds` ("Earnings by field of study are
the U.S. Treasury's, for students who finished in 2017-18 and 2018-19, in 2024
dollars; federal loan debt and monthly payment by field of study are the federal
student loan system's, for students who finished in 2018-19 and 2019-20." ≈ 38
words) and the caption shrinks to "Biology programs at Lakeshore State
University" (6): Biology 81, Chemistry 59, Lakeshore 140 + 38 once per page.

| Fact                                                                       | Now                                                                                                                                                                                   |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Subject lead "Biology, which your student wants to study:"                 | caption ("Biology programs at …"); "which your student wants to study" is the subject's reason for being on the page — keep it in the first caption or in the kinds coverage sentence |
| Subject marks (no program / all withheld)                                  | one `<p>`, unchanged (no figures)                                                                                                                                                     |
| Program title                                                              | row header                                                                                                                                                                            |
| Three figures, their units and moments                                     | three cells; "a year", "at finishing", "monthly … 10-year plan" in headers (R3)                                                                                                       |
| Publisher per measure                                                      | caption (or kinds once)                                                                                                                                                               |
| Cohort years, inflation base year                                          | caption (or kinds once)                                                                                                                                                               |
| Imputed variant ("… estimated this figure; the school did not report it.") | cell footnote marker + one sentence under the table, or the sentence in the cell                                                                                                      |
| Gap marks                                                                  | the cell, verbatim                                                                                                                                                                    |
| Coverage sentence, tier scope, sources paragraph                           | unchanged                                                                                                                                                                             |

Stacked card: "Program: Biology, General / Median earnings a year, four years
after finishing: $41,200 / Median federal loan debt at finishing: $26,000 /
Median monthly payment on a standard 10-year plan: $291". Reads well. The
Ecology row stacks as three label: sentence lines — long but each is one fact.
**Constraint 1 problem**: this table has two publishers (Treasury, NSLDS) in one
table; see §7.

### Price table and summary table, as they stand

Both already tables. Changes the brief's rules ask for:

- Summary table: add the unit to the header once (R3): corner cell or caption
  "Per year". Today "per year" is nowhere in it.
- Price table: the residency line and the generic tier line (45 words per
  school) can become the caption: "Per year, at the in-state tuition and fees
  this family would pay at this public school" (≈16 words). The district-price
  sentence and the two-figures-no-state sentence state an absence and stay as a
  `<p>`. The generic tier sentence ("This school publishes one tuition price in
  what we hold; where that price is for one kind of student, the tuition figure
  beside it says which", 24 words) is a reading instruction about the row label,
  not a fact — a candidate restatement, flagged for the spec because D1 is
  strict.
- The at-home "$0" note "our assumption, not a figure this school published" (8
  words × every school with an at-home column) is also the basis bullet "That is
  our assumption, not a figure any school published." Hoist to the column header
  once per table ("living at home (housing and food counted as $0, our
  assumption)") or keep one `report-note` per table as a footnote row. Either
  keeps the fact reachable beside the figure.

## What the page would measure afterwards

Arithmetic over the block table in §2, substituting the mocks of §4. Kinds
additions: K1 merit-grant 11, K2 borrowing-cohort 24, K3 Scorecard-no-year 13,
K4 National Merit paid/considered 25 (only when a block needs them).

### Three-school verbose render

| Block                                       | Today (L / E / W)     | After (L / E / W)                                |      In `<table>` after |
| ------------------------------------------- | --------------------- | ------------------------------------------------ | ----------------------: |
| h1 + live line                              | 18                    | 18                                               |                       0 |
| basis                                       | 283                   | 283                                              |                       0 |
| summary                                     | 125                   | 125                                              |                      43 |
| school intro + price table                  | 119 / 102 / 102       | 119 / 102 / 102                                  |            57 × 3 = 171 |
| merit                                       | 54 / 53 / 53 = 160    | 36 / 35 / 35 = 106                               |       26 + 25 + 25 = 76 |
| debt (Scorecard sentence + borrowing table) | 112 / 111 / 111 = 334 | (21+40) / (21+39) / (21+39) = 61 / 60 / 60 = 181 |      40 + 39 + 39 = 118 |
| kinds                                       | 41                    | 41 + 11 + 24 + 13 = 89                           |                       0 |
| sources                                     | 120                   | 120                                              |                       0 |
| **Total**                                   | **1,404**             | 18+283+125+323+106+181+89+120 = **1,245**        | 43+171+76+118 = **408** |

Prose after = 1,245 − 408 = **837**; prose share **67.2%** (today 84.8%). Per
school: Lakeshore 119+36+61 = 216 (today 285), Eastbrook 102+35+60 = 197 (266),
Wynnford 197 (266). Words removed: 159; words moved into tables: 194.

With the generic tier sentence dropped at the two single-tier schools (−24 × 2):
total 1,197, prose 789, share 65.9%.

R2 after: 0 (today 0). Standing prose that no table touches: 18 + 283 + 82
(summary hints) + 62 + 45 + 45 (school intros) + 89 + 120 = 744 words, 59.8% of
the after-page. **The prose share cannot fall below ~60% on this fixture without
touching the basis list, the summary hints, the school intro lines or the
sources section** (candidate C territory). The brief's own criterion is the
shape rule, not the share; the share is printed, never asserted.

### Richest National Merit render

| Block                                | Today                    | After, NM as table        | After, NM as `<dl>` |
| ------------------------------------ | ------------------------ | ------------------------- | ------------------- |
| h1, basis, summary, schools, sources | 18+277+128+290+120 = 833 | 833                       | 833                 |
| merit                                | 54 / 53 / 54 = 161       | 36 / 35 / 36 = 107        | 107                 |
| national-merit                       | 71 / 71 = 142            | 64 / 64 = 128             | 56 / 56 = 112       |
| debt (Scorecard)                     | 29 × 3 = 87              | 21 × 3 = 63               | 63                  |
| kinds                                | 91                       | 91 + 11 + 13 + 25 = 140   | 140                 |
| **Total**                            | **1,314**                | **1,271**                 | **1,255**           |
| In `<table>`                         | 164                      | 164 + 77 + (58 × 2) = 357 | 164 + 77 = 241      |
| Prose / share                        | 1,150 / 87.5%            | 914 / **71.9%**           | 1,014 / **80.8%**   |

R2 after: 0 in either form (and the `<li>`-wrapper false positive disappears
with the `<li>`). Per school (R1 / Ashford / R2): table form 78+36+64+21 = 199,
76+35+64+21 = 196, 136+36+21 = 193 (today 232 / 229 / 219).

### Richest award-grid render

Merit 161 → 107, award grid 43 × 2 → 46 × 2, debt 87 → 63, kinds 56 → 80 (K1,
K3). Total 1,223 → 18+277+128+290+107+92+63+80+120 = **1,175**; in `<table>`
164 + 77 + 41 × 2 = 323; prose 852; share **72.5%** (today 86.6%). A real grid
averages ~14 rows (571 committed cells across 40 curated colleges, per the
renderer's own comment): today ≈ 35 + 14 × 8 = 147 words per grid; table ≈ 41 +
14 × 5 = 111.

### Program-return page (reconstructed)

Lakeshore 232 → 216 (two 44-word captions) or 140 + 38 once (cohorts in kinds);
R2 violations 1 → 0. The saving is modest in words and total in shape: the
90-word three-figure paragraph becomes a 5-word row.

## The acceptance criterion, as a test

In the style of `CostReportPageTest` ("a three-school report's word count is
measured and printed, never asserted") and `ReportProse.kt`'s `reportSectionOf`
/ `proseOf` / `visibleProseOf` / `countOf`. Kotlin-shaped; not compiled.

Currency figure = `Regex("\\$\\d[\\d,]*(?:\\.\\d+)?")` — a dollar sign, a digit,
then digits and commas, optional decimals. It counts "$8,000-
$12,000" as two, "$0" as one, "25%" and "394" as none. It is the regex
`blocks.py` used for every count in this report.

```kotlin
// ReportProse.kt additions ------------------------------------------------

private val CURRENCY = Regex("\\$\\d[\\d,]*(?:\\.\\d+)?")

/**
 * The innermost paragraphs a parent reads: every <p>, and every <li> with its
 * <p> children removed first. A National Merit <li> wraps five <p>s; counted
 * whole it carries three currency figures and no sentence in it carries more
 * than two. The unit of R2 is the sentence a parent reads, so nested block
 * children are stripped before counting.
 */
internal fun paragraphsOf(report: String): List<String> {
  val ps = Regex("<p\\b[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(report).map { it.groupValues[1] }
  val lis =
    Regex("<li\\b[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
      .findAll(report)
      .map { it.groupValues[1].replace(Regex("<p\\b[^>]*>.*?</p>", RegexOption.DOT_MATCHES_ALL), " ") }
  return (ps + lis).map { proseOf(it).trim() }.filter { it.isNotEmpty() }.toList()
}

internal fun currencyFiguresIn(text: String): Int = CURRENCY.findAll(text).count()

/** Words inside <table>…</table>, the test rule applied to each table's markup. */
internal fun tableWordsOf(report: String): Int =
  Regex("<table\\b.*?</table>", RegexOption.DOT_MATCHES_ALL)
    .findAll(report)
    .sumOf { proseOf(it.value).split(Regex("\\s+")).count { w -> w.isNotBlank() } }

// CostReportPageTest.kt ----------------------------------------------------

@Test
fun `no paragraph in the report carries three or more currency figures`() {
  // R2 (brief 0016). A quotation of the school's own words is exempt only
  // where it sits in a table cell; a <p> never quotes three figures.
  listOf(
    costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney()),
    meritRenderProfile(), awardGridRenderProfile(), programReturnProfile(),
  ).forEach { profile ->
    val report = reportSectionOf(render(profile))
    val offenders = paragraphsOf(report).filter { currencyFiguresIn(it) >= 3 }
    assertEquals(emptyList(), offenders, "a paragraph holds three or more currency figures; they belong in a table: ")
  }
}

@Test
fun `every table in a school section names one publisher and never totals across publishers`() {
  val report = reportSectionOf(render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney())))
  val schoolSections = report.split("<section class=\"report-school\">").drop(1)
  schoolSections.forEach { section ->
    val tables = Regex("<table\\b[^>]*>.*?</table>", RegexOption.DOT_MATCHES_ALL).findAll(section).map { it.value }.toList()
    tables.forEach { table ->
      // One caption, one publisher attribute. The attribute is the MoneySource
      // family or citation kind the renderer already holds for the table's
      // figures: "ipeds-scorecard" for the price table (all U.S. Department of
      // Education), "common-data-set" for merit and borrowing, "scorecard" for
      // the federal debt row, "school-page" for the two scholarship tables.
      assertEquals(1, countOf(table, "<caption"), "exactly one caption per table: [$table]")
      val publisher = Regex("data-publisher=\"([^\"]+)\"").find(table)?.groupValues?.get(1)
      assertNotNull(publisher, "every school-section table names its publisher in markup: [$table]")
      // No total row where the publisher is not the school's own cost lines.
      if (publisher != "ipeds-scorecard") {
        assertFalse(table.contains("report-row-total"), "a total row across a publisher's separate figures: [$table]")
      }
    }
    // The federal figure and the school's filing are never in one table.
    val cds = BorrowingWire.HEADER_TAIL.take(20) // or the borrowing citation's citedAs
    tables.forEach { table ->
      assertFalse(
        table.contains("College Scorecard") && table.contains("Common Data Set"),
        "the Scorecard figure and the school's Common Data Set figures share a table: [$table]",
      )
    }
    // Still true after the change (RFC 175 D9 test): no sentence compares them.
    assertEquals(emptyList(), figureComparisonWordsIn(proseOf(section)))
  }
}

@Test
fun `a three-school report's prose share is measured and printed, never asserted`() {
  // Brief 0016. Printed beside the RFC 203 word count:
  //   grep -h MEASURE public-web/build/test-results/test/*.xml
  val report = reportSectionOf(render(costProfile(listOf(verboseState(), verboseCampus(), verbosePrivate()), answeredMoney())))
  val words = visibleWordsOf(report)
  val inTables = tableWordsOf(report)
  val prose = words - inTables
  println(
    "[MEASURE] three-school cost report: [$words] visible words, [$inTables] in tables, [$prose] in prose " +
      "(prose share ${"%.1f".format(100.0 * prose / words)}%; 2026-10-25 baseline 1404 / 214 / 1190 / 84.8%)",
  )
}
```

Notes on (b): today's markup has no `<caption>` and no `data-publisher`; the
test needs the renderer to emit both, which is the change itself. "Never
compared" already has a helper (`figureComparisonWordsIn`, used by
`CostReportProgramReturnTest`). The R2 test's unit must be the innermost
`<p>`/`<li>`; without the strip, today's National Merit `<li>` fails it for a
reason no parent can see.

R3 ("units in the header once") as a test: for each table, assert no `<td>` text
matches `Regex("\\$[\\d,]+ (a|per) year")` — a unit phrase in a cell means the
header does not carry it. A cell that is the school's own quoted words ("$6,000
a year" never is; `awardAsPublished` may be) would need an exemption, so the
assertion is best scoped to cells the renderer formats itself
(`class="report-value"`), not to `report-quoted` cells.

## Findings that change the plan

1. **R2 is already clean on today's page, except program returns.** Across all
   98 dumps, no `<p>` carries three or more currency figures; the only true
   violator is the program paragraph (`programParagraph` joins three figure
   sentences into one `<p>`), which no dump renders. R2 alone will not drive the
   merit/borrowing change the brief wants; what moves is prose share (84.8% →
   ~67%) and repeated frame (375 words re-read across schools 2 and 3).
   Recommend pairing R2 with a stronger shape rule the page can actually fail
   today: _no currency figure appears in a `<p>` or `<li>` inside
   `section.report-school` except inside a cell or a quotation of the school's
   own words._ Today that rule fails 12 times on the verbose render (one figure
   in each merit block, three in each debt block, three schools).

2. **A naive `<li>` count false-positives on National Merit.** Nine `<li>`
   wrappers across the dumps hold three `$` because they wrap five `<p>`s.
   Either the test strips nested block children (pseudocode above) or the block
   stops nesting `<p>` in `<li>` (the `<dl>` form does both).

3. **Federal debt: keep the sentence.** One figure; 21 words as a sentence (with
   the no-year hedge hoisted) vs 20 as a one-cell table plus table chrome, and a
   one-cell table beside a two-row table invites exactly the visual adjacency
   constraint 1 exists to prevent. It must never join the price table (its
   "Total per year" row) or the borrowing table. If the spec wants "every figure
   in a cell", give it its own captioned table and accept the chrome.

4. **Merit: table or definition list, marginal either way.** Two figures; the
   saving (54 → 36 per school) is mostly the 17-word hedge moving to the kinds
   section. A `<dl>` of two pairs is the same words with no table. The table is
   justified only by uniformity with the borrowing table beneath it.

5. **Borrowing: the clear win.** 75 → 40 words per school, four figure sentences
   become two rows, the denominator "of the 1,000 who graduated" is said once in
   the header (R3), and the 24-word cohort hedge is said once per page instead
   of once per school. Two notes: the page prints the graduating class
   unformatted ("1000") today because `shareLabel` interpolates the raw Int — a
   table header will want "1,000"; and the four gap/variant sentences are
   coach-voiced ("say that the figure is missing from our data, never that this
   school does not report it") and have no cell shape — they stay as `<p>` under
   the table, and the spec should say so.

6. **National Merit rows are words, not numbers.** Of 71 words per row, 9 are
   figure tokens. A six-column table is 64 words, reads badly at desktop width
   (two columns are sentences), and leaves empty money cells for a words-only
   school. Recommend a `<dl>` per row (56 words, labels in markup, identical
   reading on phone and desktop) with the guarantee collapsed to "Paid" /
   "Considered" and its two sentences said once in `report-kinds`. Not a table.

7. **Award grid: already row-shaped; worth a table for real grids.** A one-row
   fixture grid costs 3 more words as a table (46 vs 43); a real grid (~14 rows)
   saves ~36. The "Pays a year" column mixes money and the school's words
   ("tuition and fees") — verbatim words in a money column is right (the
   renderer's own rule: the top bands are words), and the header unit must not
   be read as claiming the words are per year when they are not ("The award
   amount varies from $8,000- $12,000 annually" says its own period). Empty
   GPA/score cells need hiding on the card (`[data-label]:empty`), or the
   stylesheet prints "GPA: " alone.

8. **Program returns break constraint 1 as written.** Three unlike units (a year
   / at finishing / a month) from two publishers (U.S. Treasury; federal student
   loan system) with two cohorts. "One table per publisher" literally means two
   tables per subject per school. Recommend one table with publisher and cohort
   in the column header (or once per page in `report-kinds`, since cohorts are
   release-wide constants) and restating the constraint as "one publisher per
   table, or per column with the publisher in that header; never a total row
   across them". Both publishers are the same Scorecard file and the sources
   paragraph already says they are never added or compared. If the brief keeps
   program returns as prose (candidate A), the page keeps its only R2 violation,
   so candidate A cannot pass the brief's own criterion on a page with a matched
   program.

9. **The summary table has no unit (R3 gap today)**: "per year" appears nowhere
   in "The schools side by side". A caption or corner cell fixes it.

10. **The standing prose floor is ~744 words.** Basis list 283, sources 120,
    kinds ~89 after hoisting, summary hints 82, school intro lines 152, h1 18.
    None of it is touched by candidates A or B, so the three-school prose share
    bottoms out near 60–67%. If a lower share is wanted, that is candidate C
    (basis list → only the lines that change a number; intro lines → the
    price-table caption), not more tables.

11. **The generic tier sentence is a restatement candidate** (24 words × every
    single-tier school; 48 on the fixture): "This school publishes one tuition
    price in what we hold; where that price is for one kind of student, the
    tuition figure beside it says which." It instructs the reader to look at the
    row label; the row label is on the page. The district-price sentence and the
    no-state sentences are facts and stay. D1 makes this the spec's call, not
    this report's.

12. **The at-home "$0" note is said per cell and in the basis list.** "our
    assumption, not a figure this school published" × 3 schools + "That is our
    assumption, not a figure any school published." in the basis. Hoisting to
    the column header once per table keeps it beside the figure and removes 16
    words on the fixture.

13. **Merit's assurance tier is unspoken today.** `tierKindsSection` covers only
    prose figures (federal debt, programs); the merit CDS figures carry no tier
    sentence anywhere on the page. Not a loss the change causes, but if a
    row-shaped seam hands the page a `CoveredFigure` for merit, the frozen
    voluntary-self-report sentence ("The college published this about itself. No
    one checks it. We link to the college's own file so you can see it.")
    becomes sayable once per page, and its "We link" clause is already true of
    the caption link.

14. **Measurement discrepancy, resolved.** 1,403 (brief) vs 1,404 (test) is
    tokenization at tag boundaries; use the test's `proseOf` for every figure
    the spec quotes, so the printed `[MEASURE]` line and the spec agree.
