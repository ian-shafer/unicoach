# 0016 — The report at a glance

**Handle:** `glance` (brief 0016)

## Status

- **Phase:** EXECUTE — gates 1 and 2 passed 2026-10-26; five slices in
  `spec.md`; `glance/01` LANDED (RFC 260), so `glance/02`, `glance/03`,
  `glance/04` and `glance/05` are unblocked (D12). /slice appends the ledger
  below after each land.
- **Gate 1 (prioritisation):** **PASSED 2026-10-26 — Ian approved all eight
  defaults, verbatim: "approve all"**, after two questions and one amendment
  recorded under D3 below. D1-D8 stand as written. D8 is `standing` for the
  Family Cost Report.
- **Gate 2 (spec):** **PASSED 2026-10-26 — "approve"**; D9-D13 in `spec.md`, all
  defaults.
- **Research:** 3 parallel reports delivered 2026-10-25 —
  `research/table-anatomy.md` (802 lines, external, every fetch and failure
  listed in its METHOD), `research/row-shaped-seams.md` (1,125 lines, internal,
  every claim `path:line`), `research/page-inventory.md` (961 lines, measured on
  62 distinct real renders of `main`, mocks with information-loss checklists).
- **Ledger:**
  - `glance/01/borrowing-and-merit-in-rows` LANDED as RFC 260 (`main@135fc796`,
    2026-10-27) — on the Family Cost Report each school's merit aid is now a
    two-row table and its graduates' borrowing a table with one row per loan
    type, each captioned once with a link to the school's own Common Data Set
    filing. The hedges every school repeated are said once per page in "What
    kind of numbers these are" (K1 merit is a grant, K2 the borrowing cohort, K3
    the Scorecard debt is undated), and the merit figures now get their
    self-report kind sentence (D10). The federal median debt stays one sentence
    (D4). Lands the conventions every later `glance` slice inherits: a
    `<caption>` and `data-publisher` on every report table, and an asserted
    shape rule (`CostReportShapeTest`) with two shrinking exemption sets — the
    unconverted blocks (award grid, National Merit, program returns) and the
    still-uncaptioned price and summary tables. The coach is untouched (D8).
    Measured, not asserted: the three-school page went from 1,404 visible words
    (214 in tables) to 1,299 (423 in tables). Spec drift found: the caption rule
    needed its own exemption set (`UNCAPTIONED_TABLES`, RFC 260 D-B) until
    `glance/02` captions those two tables. 36 lenses, 12 should-fix findings
    fixed; full-scope hook passed.
  - Follow-up `main@76380078` (2026-10-29, Ian's call): the page-wide caption
    assertion, its `UNCAPTIONED_TABLES` exemption set and the `data-publisher`
    attribute are removed. Captions stay, each pinned by its own table's tests;
    `glance/02` no longer has an exemption set to empty.

## The question

Ian's framing, verbatim (2026-10-25):

> The parent reports are important. They're the first touchpoint to get parents
> signed up into unicoach. But, the reports are verbose. I think they should be
> much more table-based. WDYT?

The answer given, and the kickoff Ian approved verbatim ("Yes, kick off a /chart
brief for making the Family Cost Report table-first, with the constraints you
listed"):

> Make the Family Cost Report table-first. Today's three-school render is 1,403
> visible words, 214 in tables and 1,189 in sentences; each school's section is
> ~300 words of which ~60 are its price table, the rest prose (merit, debt,
> borrowing, National Merit, award grid). Constraints: one table per publisher
> with the publisher in the caption and no total row (RFC 175 D9 / brief 0009 D9
> never-sum rule holds); per-figure hedges move to column headers or the
> once-per-page "What kind of numbers these are" section; the byte-frozen
> AssuranceTierCopy sentences are untouched; the page reads structured figures
> through a row-shaped seam while the coach keeps its sentences ("one figure
> seam, two renderings"); verify the 640px stacked-card form on a phone;
> acceptance criterion in the form of concision-craft R2 (no paragraph with 3+
> currency figures / prose share) rather than a word ceiling.

Why it matters now: since RFC 254 (`family/10/claim-from-the-report`) the Family
Cost Report is the parent's on-ramp — the page a student shares, with no account
needed, that now ends in "Keep going with your own account". It is the first
thing a parent reads from unicoach and the thing that decides whether they sign
up. Brief 0009 ("Say it once") removed its repetition (1,767 → 1,404 words on
the three-school fixture) and deliberately stopped short of changing its shape.
What remains is shape: the figures a parent came for are inside sentences.

Fixed constraints, restated as requirements this brief treats as **given** (they
are Ian's approved words, not open decisions):

1. **One table per publisher, publisher in the caption, no total row.** RFC 175
   D9 ("two publishers with two vintages share one section ... never summed,
   never differenced, and never presented as one debt number") and brief 0009 D9
   ("Do not merge the merit and debt tables") both hold. The never-sum rule
   argues against ONE table, not against tables.
2. **Per-figure hedges move up**, to a column header or to the once-per-page
   "What kind of numbers these are" section (`report-kinds`), which already
   exists for that job (RFC 191).
3. **The byte-frozen `AssuranceTierCopy` sentences are untouched.** They are
   frozen against the immutable prompt rows and are already said once per page.
4. **One figure seam, two renderings.** The page reads structured figures
   through a row-shaped seam; the coach keeps its sentences. Page and coach
   still agree on every figure, cohort and publisher; they stop sharing prose.
5. **The 640px stacked-card form is verified on a phone**, not assumed: the
   parent most likely opens the link from a text message.
6. **The acceptance criterion is a shape rule, not a word ceiling.** RFC 203
   deleted the word ceiling because every slice ratcheted it to what it had just
   measured. concision-craft R2 ("no rendered paragraph contains ≥3 currency
   values") and R3 ("units live in the header, once") are the form.
7. Everything brief 0009 D1 (information loss) and D8 (standing: say it once,
   compression never removes reachability) binds, still binds. No figure,
   status, tier, year, publisher or unicoach assumption becomes unreachable.

## What the repo already has (grepped and rendered, not guessed)

Measured 2026-10-25 by rendering `main` in a throwaway worktree through the same
route `CostReportPageTest` drives, on its three-school "verbose" fixture (every
school priced three ways, with a merit filing and a borrowing filing):

| Three-school render         | Words |
| --------------------------- | ----: |
| Visible, in the report      | 1,403 |
| Inside `<table>` cells      |   214 |
| In sentences and list items | 1,189 |
| One school's section        |  ~307 |
| ...of which its price table |   ~60 |

The page's measurement test (`CostReportPageTest`, "a three-school report's word
count is measured and printed, never asserted") printed the same 1,404 on
2026-09-23, before RFCs 247 and 251 added National Merit and program-return
blocks; those blocks do not fire on this fixture.

| Foundation                                                                                                                                                                                                                                                                                                 | Where                                                                                                                                                                                     |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The page renderer: one Kotlin HTML-DSL file, 2,403 lines, with the section order `assumptions → summary table → per school (residency line, tier line, price table, merit, award grids, National Merit, debt + programs + borrowing) → kinds → sources → claim offer`                                      | `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` (`schoolSection` at :852)                                                                                           |
| Two tables already exist: the cross-school summary ("The schools side by side", RFC 155) and each school's price table (rows = cost lines, columns = ways of living)                                                                                                                                       | `summaryTable` :736, `priceTable` :937                                                                                                                                                    |
| Everything after the price table is `<p>` and `<li>`: merit (2 sentences + source), award grid (`<ul>` of "name — score — pays" lines), National Merit (6–7 lines per row), debt (1 sentence), program returns (1 paragraph per program, 3 figures each), borrowing (header + 4 sentences)                 | `meritBlock` :1424, `awardGridBlock` :1621, `meritRowLines` :1838, `debtBlock` :1965, `programParagraph` :2028, `borrowingParagraphs` :2225                                               |
| The prose is the coach's prose: `BorrowingWire.blockOf` hands the page the same sentences the coach says (RFC 175 D10 → RFC 190); `MeritAidWire` labels, `ProgramReturnCopy` sentences and `AssuranceTierCopy` tier sentences are the one home of each, shared by page and chat                            | `service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt:250`, `.../admissions/MeritAidWire.kt`, `.../costs/ProgramReturnCopy.kt`, `.../costs/canonical/AssuranceTierCopy.kt` |
| The structured values behind the sentences exist: `BorrowingCoverage.Reported` (per-loan-type average and share), `PublishedPackage.Priced` (per-year, total, years, base rate), `AutomaticAwardBand` (name, score axis, amount or words), `ProgramReturnProgram`, `CollegeCost.medianDebtAtCompletionUsd` | `BorrowingWire.kt:39` (`BorrowingBlock`), `NationalMeritService.kt:247`, `db/.../AutomaticAwardBand`, `CostReportPage.kt` imports                                                         |
| Byte-frozen copy: `AssuranceTierCopy` and `FigureStatusCopy` sentences are pinned against immutable `system_prompts` rows by `SystemPromptCatalogTest`; the page already says them once, in "What kind of numbers these are" (`tierKindsSection` :2160, RFC 191)                                           | `AssuranceTierCopy.kt:125`, `FigureStatusCopy.kt:89`                                                                                                                                      |
| A data table already stacks into one card per row below 640px, column header restored beside each value via `data-label`; written once for the report's schools and /fafsa's jurisdictions                                                                                                                 | `public-web/src/main/resources/static/site.css:644-687`                                                                                                                                   |
| The never-sum rule has a test: RFC 175's report-page test asserts the federal Scorecard figure and the school's CDS figures "are never summed or compared"                                                                                                                                                 | `CostReportPageTest`; RFC 175 D9 at `rfc/175-*.md:109`                                                                                                                                    |
| Brief 0009 rated "merge merit + debt + borrowing into one table per school" (its option O5) HIGH RISK for exactly that reason and did not do it; its concision-craft research recommends R2/R3 (parallel figures in a table, units in the header) with tests, never adopted as a slice                     | `product/0009-say-it-once/research/report-verbosity.md:365`, `research/concision-craft.md:399-470`, `spec.md:550` (D9)                                                                    |
| The word ceiling is gone, the measurement stays: RFC 203 deleted `WORD_CEILING` after it moved 1,767 → 1,530 → 1,450 → 1,401 → 1,404, the last step upward                                                                                                                                                 | `CostReportPageTest:1095-1115`                                                                                                                                                            |
| The page now ends in the parent's sign-up door (RFC 254): "Keep going with your own account", below the sources section, naming no student (brief 0014 D-F)                                                                                                                                                | `CostReportPage.kt:580-636` (`reportClaim`, `claimOffer`)                                                                                                                                 |
| Standing decisions this brief inherits: 0001 D12 (value before ask), 0009 D1 (information loss) and D8 (say it once, standing), 0011 D23 (caveat budget: a limitation earns copy in proportion to how often it bites), 0008 D7 (no confidence numbers, ever)                                               | `product/0001-*/spec.md:141`, `product/0009-*/brief.md:743,788`, `product/0011-*/brief.md:311`                                                                                            |

**The one structural gap**, in a sentence: every post-price-table fact on the
page reaches the renderer as a finished sentence, built by a seam the coach also
speaks, so the page cannot lay a figure in a cell without either parsing the
sentence (never) or being handed the figure (the row-shaped seam, which does not
exist yet).

## Success criteria for this brief's decision

1. A parent opening the shared link sees, per school, the figures first: what it
   costs, what it gives, what graduates owe — each in a table whose caption
   names the publisher and cohort once — and reaches the sign-up door having
   scrolled past tables, not paragraphs.
2. No fact is lost (0009 D1): every figure, status, assurance tier, cited year,
   publisher and unicoach assumption on today's page is on the new page, and the
   never-sum rule is visibly kept (no shared table, no total row across
   publishers).
3. The criterion is testable on rendered output and does not ratchet: no
   paragraph in the report carries three or more currency figures; units sit in
   headers; prose share of the page is measured and printed.
4. The coach's answers do not change: its sentences, its prompt version and its
   byte-frozen copy are untouched; page and coach read the same figures.
5. The phone rendering is checked for real at 640px and below, with each table
   legible as stacked cards.

## Candidates

Ian chose the bet; the candidates are about SCOPE and SHAPE of what lands.

| # | Scope candidate                                   | What lands                                                                                                                                                                                                                                                                            | Existing foundation                                                                                                |
| - | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| A | Money-after-the-price tables                      | Per school: a merit table (CDS caption), a borrowing table (CDS caption, loan type × borrowed / average owed), a federal debt row under its own Scorecard caption. Hedges hoisted to captions/headers. Program returns stay prose.                                                    | `BorrowingBlock` carries the citation and structured figures; `report-kinds` exists for hoisted hedges             |
| B | A + the scholarship blocks                        | National Merit rows → one table per school (standing × entering class × pays × guaranteed × first choice × apply by); the automatic-award grid `<ul>` → a three-column table (award, score band, pays); program returns → one table per subject (program × earnings × debt × payment) | `PrintableMeritRow`, `AutomaticAwardBand`, `ProgramReturnProgram` are already typed values                         |
| C | B + the top of the page                           | The side-by-side summary gains a column per publisher-safe figure (merit share, median federal debt) so the first screen answers more; the assumption list shrinks to the lines that change a number, the rest moves to `report-kinds`                                                | `summaryTable` and `AssuranceTierGrouping` exist; 0009 D5 forbids hiding anything band-specific or number-changing |
| D | A one-screen family summary card above everything | A new block at the top: per school one line of the three figures a parent asks first, then the detail below                                                                                                                                                                           | Nothing: a new composition, and a second place saying each figure (0009 D8 risk)                                   |

## Research (PRIORITISE input)

Three parallel research agents, one per genuinely independent question; each
writes `research/<topic>.md` with method and citations:

- `research/table-anatomy.md` — external. How the established money surfaces
  (College Scorecard, the College Financing Plan, CFPB, Niche, BigFuture,
  GOV.UK/digital.gov guidance) lay out per-institution financial tables from
  more than one publisher; caption and header conventions that stop two adjacent
  tables reading as one sum; stacked-card patterns on phones and their failure
  modes; accessibility floor (`caption`, `scope`, `headers`); evidence on what a
  parent scans for first.
- `research/row-shaped-seams.md` — internal, read-only. For every prose block
  after the price table: the typed value it is built from, the copy seam that
  turns it into sentences, every other consumer of that seam (coach tools,
  prompt rows, tests), which strings are byte-frozen and by which test, which
  page tests assert exact sentences, and what the smallest row-shaped seam looks
  like; plus whether any live branch touches the page (a CONFLICTS edge).
- `research/page-inventory.md` — measured. Today's rendered page block by block:
  words, currency figures per paragraph (R2 violations), repeated frames versus
  facts; a table-first mock of each block with every fact present and counted;
  the acceptance criterion written as a test; and what the three-school page
  would measure afterwards.

## Research synthesis (read the reports; this is the ranked view)

The three reports agree on the shape and correct the brief in four places.

| #  | Finding                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Table anatomy (external)                                                                                                                                                                                              | Row-shaped seams (internal)                                                                                                                                                                                                          | Page inventory (measured)                                                                                                                                                                                                                                                                    |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | **The borrowing block is the clear win; the rest is marginal in words and total in shape.** Four figure sentences become two rows; the denominator is said once in a header; the cohort hedge once per page.                                                                                                                                                                                                                                                 | Scorecard and College Navigator put one component per table with the cohort in the caption; GAO (2022) and uAspire found 58–70% of aid letters mix types — labelled rows per type is the documented fix               | `BorrowingWire.blockOf` is page-only (its sole caller is the page), so a row seam beside it shares the same figure functions and the coach's `objectOf` payload is untouched                                                         | 75 → 40 words per school; three-school page 1,404 → 1,245 words, prose share 84.8% → 67.2%; repeated frame 375 words today, the debt block alone re-read 197                                                                                                                                 |
| 2  | **One table per publisher holds for merit, borrowing and the federal debt figure — and breaks on program returns**, which carry two publishers (U.S. Treasury earnings; the federal loan system's debt and payment) with two cohorts in one logical row per program.                                                                                                                                                                                         | Scorecard never puts net price and debt in one table; its own documentation and the 2016 technical review panel record that the same element differs by cohort and definition across federal sites                    | `ProgramReturnCopy.kt:285-289`: earnings and debt figures carry different publishers and cohorts; "one table per publisher" literally means two tables per subject per school                                                        | Program returns are the page's ONLY paragraph with three currency figures today; keeping them as prose fails the brief's own criterion, so candidate A cannot pass it                                                                                                                        |
| 3  | **The criterion as written (no paragraph with 3+ currency figures) is already green today**, except on program returns. It asserts nothing about merit or borrowing.                                                                                                                                                                                                                                                                                         | —                                                                                                                                                                                                                     | The measurement test and `ReportProse` helpers exist to host a stronger rule in the same never-asserted style                                                                                                                        | Measured across 62 renders: zero violations outside program returns. A rule the page fails today: no currency figure in a `<p>`/`<li>` inside a school section outside a cell or the school's quoted words (fails 12× on the verbose render)                                                 |
| 4  | **Not every block wants a table.** One figure is a sentence; words-not-numbers facts are a definition list.                                                                                                                                                                                                                                                                                                                                                  | GOV.UK summary list / USWDS: key facts are a `<dl>`, not a table; NN/g: at most two items compare on a phone                                                                                                          | Award grid and National Merit have no copy seam and are already row types (`AutomaticAwardBand`, `PrintableMeritRow`); conversion is page work plus rewriting exact `<li>` asserts                                                   | Federal debt: 21 words as a sentence vs 20 as a one-cell table plus chrome, and a one-cell table beside the borrowing table invites the adjacency the never-sum rule exists to prevent. National Merit: 9 figure tokens in 71 words; a six-column grid reads badly on desktop                |
| 5  | **Phones: stack the wide table, keep two-column tables as grids, and keep table semantics when stacking.**                                                                                                                                                                                                                                                                                                                                                   | USWDS: tables with more than two columns scroll or stack; `data-label` must equal the header text; Roselli/NHS: `display:block` stacking needs explicit table roles and a visually-hidden (not `display:none`) header | The page already sets `data-label` on every value cell and `scope` on every `th`; no report table has a `<caption>` yet (only `/fafsa`'s explainer does); today's stacking rule is `thead { display: none }` for every `.data-table` | Every mock is written out as a stacked card; a merit or borrowing row reads well stacked, a National Merit row reads badly as a grid; empty cells print "GPA: " alone unless hidden                                                                                                          |
| 6  | **The freeze is not where the brief said.** `AssuranceTierCopy` is frozen by its own test, not a prompt row; what `SystemPromptCatalogTest` pins is the six `FigureStatusCopy` sentences — which the page prints inside blank cells and gap marks. So a blank CELL must keep its sentence verbatim.                                                                                                                                                          | Scorecard, BigFuture and the Financing Plan spell out a missing value as a fixed phrase, never a dash or "N/A"                                                                                                        | `AssuranceTierCopyTest.kt:113-118, 171-194`; `SystemPromptCatalogTest.kt:3283-3308`; `ProgramReturnCopy.kt:209-211`                                                                                                                  | Every gap variant is listed as a fact with its sentence quoted; the mocks keep each one in its cell or as the `<p>` under the table                                                                                                                                                          |
| 7  | **A table makes two facts reachable that the page drops today**: merit's freshman denominator (`fullTimeFreshmen`, `nonNeedMeritRecipients`) and the borrowing block's graduating-class label are on the wire and not on the page.                                                                                                                                                                                                                           | —                                                                                                                                                                                                                     | `MeritAidWire.kt:55-56`, `BorrowingWire.kt:201-202`                                                                                                                                                                                  | The borrowing mock already prints "of the 1,000 who graduated" in the header (today "1000", unformatted)                                                                                                                                                                                     |
| 8  | **The prose floor is ~744 words** — the assumption list (283), sources (120), kinds (~89), summary hints (82), school intro lines (152) — so the three-school share bottoms near 60–67% unless the top of the page changes (candidate C). Two cheap pieces of C are defects today: the summary table has no unit anywhere, and the at-home "$0" assumption is said per cell AND in the assumption list.                                                      | Analysis Function / CFPB: units in headers once; source under the table                                                                                                                                               | —                                                                                                                                                                                                                                    | §5 arithmetic; the generic tier sentence ("This school publishes one tuition price in what we hold; where that price is for one kind of student, the tuition figure beside it says which", 24 words × single-tier school) is a reading instruction, not a fact — a restatement under 0009 D1 |
| 9  | **No live branch collides.** `pipeline/rfc-258` rewrites `CollegeCostChatTool.kt` wholesale (a second coach consumer of the cost payload) — a CONFLICTS edge only if a slice here edits that file, which the "coach untouched" constraint rules out.                                                                                                                                                                                                         | —                                                                                                                                                                                                                     | `git diff --name-only main...<branch>` for every live branch: none touches the page, its CSS, its tests, or the three copy seams                                                                                                     | —                                                                                                                                                                                                                                                                                            |
| 10 | **What a parent reads first is not evidenced for parents.** Students jump to tuition, graduation rate and earnings and do not know whether "net price" includes loans (CAP 2012); six in ten families rule schools out on sticker price (AEI 2011); 23% of adults estimate public net cost within $5,000 (Gallup/Lumina 2024). Leading with the income-band net price, and saying in every debt caption whether loans are inside, are inferred, not measured | §5 of the report says so explicitly; every claim marked evidenced or inferred                                                                                                                                         | —                                                                                                                                                                                                                                    | —                                                                                                                                                                                                                                                                                            |

**Scope verdict against the candidates: B, plus the two top-of-page defects from
finding 8** (a unit on the summary table; the at-home assumption hoisted to the
column header). A alone leaves the page's one real violation (program returns)
in place. C's larger moves — shrinking the assumption list, adding
publisher-safe columns to the summary table — change what the first screen says
and deserve their own decision after this lands. D (a one-screen card) is a
second place saying every figure, which 0009 D8 (say it once, standing) forbids.

### Corrections to the brief's own assumptions (from the reports)

1. **Constraint 3 named the wrong freeze.** The `AssuranceTierCopy` tier
   sentences are frozen by `AssuranceTierCopyTest`, not against a prompt row.
   The sentences pinned against the immutable prompt are
   `FigureStatusCopy.agentlessStatementOf`'s six, which the page prints in blank
   figures and program gap marks. Both stay verbatim; the second binds table
   cells.
2. **Constraint 1 as written cannot hold for program returns** (finding 2). D2
   below restates it.
3. **Constraint 5 ("verify the stacked-card form") assumed every table stacks.**
   The evidence says two-column tables stay grids on a phone (finding 5). D6
   below decides it.
4. **Constraint 6's R2 rule is already green** (finding 3). D3 below strengthens
   it to a rule the page fails today.

## Gate 1 decisions (APPROVED 2026-10-26)

_Approved by Ian 2026-10-26, verbatim: "approve all"._ Before approving, Ian
asked two questions, both answered in the conversation and recorded here because
one changed a default:

1. Of D3's original second rule, "no paragraph with three or more dollar
   figures": _"This does not seem like a good rule. Why would we keep it?"_
   Answer: it was kept only because the kickoff named it; today's page already
   passes it everywhere except the program paragraph, which D3's stronger rule
   also catches. **D3's default was amended before approval to drop it** (the
   text below is the amended, approved text).
2. Of D1: _"I do not understand what is being proposed here."_ Answered with a
   before-and-after of one school's section, then a side-by-side page for every
   decision (today's render, verbatim, beside the proposed shape). Ian approved
   on that page. No decision text changed.

Labels per the chart convention; the full text under each label is binding.

**D1 — scope: the blocks, plus two top-of-page defects.** _Default:_ **candidate
B plus finding 8's two defects.** Every per-school block after the price table
takes its table-first shape (merit, borrowing, federal debt per D4, National
Merit per D5, the automatic-award grid, program returns per D2); the summary
table gains its unit ("per year") once in a caption or corner cell; the at-home
"$0" assumption moves from every cell to the column header once per price table.
NOT in scope: shrinking the assumption list, adding columns to the summary
table, any one-screen summary card. Those are a later decision once a parent can
be shown this page.

**D2 — one publisher per table OR per column, named where it sits.** The
never-sum rule restated so program returns can be a table: _Default:_ **a table
holds one publisher, or one publisher per column with that publisher named in
the column header; a caption names every publisher the table holds; no table
carries a total row across publishers; the federal Scorecard debt figure and the
school's own Common Data Set borrowing figures never share a table.** Program
returns become one table per subject per school (program × earnings × federal
debt × monthly payment) with the publisher in each money column's header, and
the two cohorts and the inflation year said ONCE per page in "What kind of
numbers these are" (they are properties of the Scorecard release, identical for
every program on the page), so each caption is "Biology programs at <school>".
_Alternative:_ two tables per subject per school (earnings; debt and payment) —
44-word captions and a second table chrome per subject. _Cost of being wrong:_
none that a later slice cannot fix; the facts are identical in both shapes.

**D3 — the shape rule the page fails today.** _Default:_ **asserted, on rendered
HTML: (a) no currency figure appears in a `<p>` or `<li>` inside a school
section except inside a table cell or inside the school's own quoted words; (b)
every `<table>` has a `<caption>` as its first child naming its publisher(s) and
cohort, and a `data-publisher` attribute per publisher it holds; (c) no row
outside the price table is a total ("total", "all", "combined", "sum"), no
`<tfoot>`; (d) no table's text contains both "College Scorecard" and "Common
Data Set". Printed and never asserted, beside the existing `[MEASURE]` line: the
prose share and the per-school word count.** The brief's original R2 ("no
paragraph with 3+ currency figures") is **dropped** (Ian, 2026-10-26: "This does
not seem like a good rule"): the page already passes it everywhere but the
program paragraph, which rule (a) catches too. _Alternative:_ R2 alone — it is
green today and would assert nothing about this brief's work.

**D4 — the federal debt figure stays a sentence.** _Default:_ **one figure, one
sentence** ("The U.S. Department of Education College Scorecard reports that
students who finished here carried a median of $21,000 in federal loans."), with
"The source publishes no year for this figure" said once per page in the kinds
section. It is never a row in the price table (its "Total per year" row would
read as including it) and never in the borrowing table. _Alternative:_ a
one-cell captioned table — "every figure in a cell" at the cost of table chrome
beside the borrowing table, the adjacency the never-sum rule exists to prevent.

**D5 — table for parallel figures, definition list for key facts.** _Default:_
**merit → a two-row table (measure × figure, uniform with the borrowing table
under it); borrowing → a table (loan type × borrowed-of-N × average owed); the
automatic-award grid → a table (scholarship × test score × GPA × pays a year, a
column omitted when no band uses it); National Merit → a `<dl>` per published
row** (standing and entering class, paid/considered, a year, in total, first
choice, apply by, the school's words), with the two guarantee sentences said
once per page in the kinds section. _Alternative:_ tables everywhere — a
six-column National Merit grid with two sentence columns, two empty money cells
for a words-only school.

**D6 — phones: stack the wide table, keep narrow ones as grids, keep the
semantics.** _Default:_ **a table with three or more data columns (the price
table, the program table, the award grid) stacks into one card per row below
640px; a table with one row-header column and at most two data columns (merit,
borrowing) stays a grid; a stacked table keeps its table semantics (`role` on
table, row, cells and headers; the header row visually hidden, not
`display: none`); captions survive stacking and precede the first card; a cell
with no value is omitted or hidden on the card, never printed as "GPA: " with
nothing after it.** This changes the shared `.data-table` stacking rule that
`/fafsa` also uses; the slice verifies both pages at 375px. _Alternative:_
today's blanket rule — every table stacks — which the evidence (USWDS, NN/g)
says over-stacks two-column tables.

**D7 — restatements that go, under 0009 D1.** _Default:_ **drop** (i) the
per-school generic tier sentence "This school publishes one tuition price in
what we hold; where that price is for one kind of student, the tuition figure
beside it says which" — a reading instruction, and the row label it points at is
on the page; (ii) the merit hedge's "not an offer to this student" — the sources
section already says no figure here is an offer; (iii) every per-figure repeat
of a hedge the kinds section now says once (merit grant, borrowing cohort,
Scorecard no-year, National Merit paid/considered). **Keep** every absence
sentence that states a fact (district price not separated; two figures with no
state; "This school does not say whether a test score must be submitted"; every
blank and gap sentence, verbatim), and the residency line (it moves into the
price table's caption, not out of the page). _Cost of being wrong:_ a sentence
put back is one line.

**D8 — the coach is untouched, and the rule is standing for this page.**
_Default:_ **no coach sentence, label, payload key or prompt row changes; no
prompt version is minted; the new row seam sits beside each sentence builder and
shares its figure functions, so page and coach cannot drift. And, `standing` for
the Family Cost Report: a figure lives in a cell, a sentence carries only what a
cell cannot — a fact with no figure, or the school's own words.** Other
public-web pages (`/fafsa`, who-pays) inherit it when next touched, not by a
sweep now.
