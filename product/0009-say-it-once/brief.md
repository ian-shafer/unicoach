# 0009 — Say it once

**Handle:** `concise` (brief 0009)

## Status

- **Phase:** EXECUTE — `concise/01`, `02`, `04` and `05` LANDED 2026-09-11;
  `concise/03` HELD on a cross-brief collision
- **Gate 1 (prioritisation):** **PASSED 2026-09-11 — Ian approved all defaults,
  verbatim: "approve"**. D1-D8 stand as written below. D8 is `standing`.
- **Gate 2 (spec):** **PASSED 2026-09-11 — "approve"**; D9-D14 in `spec.md`
- **Research:** 3 parallel reports **delivered** 2026-09-11 —
  `research/report-verbosity.md` (416 lines, page rendered for real),
  `research/coach-verbosity.md` (537 lines), `research/concision-craft.md` (513
  lines, external, all sources fetched)
- **Ledger:**

  concise/01/the-page-says-it-once LANDED as RFC 188 (main@5d69aa43 + f555346f,
  2026-09-11) — the Family Cost Report page stops repeating itself. A grid row
  is said once when its figures are equal (derived, `distinct()` over the
  rendered figure, never a named role), so tuition and fees and books and
  supplies are one figure per school instead of one per way of living. The
  blended-average hint is said once per page on every shape of report, and the
  page shape itself is now derived once (`ReportShape`) rather than spelled
  three ways. Two defects fixed: the in-state clause is emitted only where a
  public school is on the page (it printed under private colleges), and the
  per-school residency sentence is dropped on a ONE-school report where the
  assumption block already states it, and stays on 2+. The income band is said
  once, in the family's own money line; the `OverallAverage` note is untouched,
  because it CORRECTS a misreading where a band note only confirms. MEASURED:
  1,767 -> 1,530 words on a three-school render (-13.4%), 800 -> 762 on one
  school, asserted as a ratchet in `CostReportPageTest`. The slice's `<= 1,480`
  criterion is NOT met and is not reachable from this slice's own decisions —
  the brief's own budget table allocates it 223 words where 313 are needed. Ian
  accepted 1,530 at the gate ("approve both") and the criterion is recorded as a
  spec defect against /chart. No wire type, no DDL, no migration, no prompt
  version; the research report's 1,793 baseline is superseded by 1,767, measured
  on today's main.

  concise/02/the-payload-says-it-once LANDED as RFC 189 (main@eed5b148 +
  9928f121, 2026-09-11) — the `college_cost_profile` payload says each
  provenance fact once. `figure_provenance` names the (assurance tier,
  publisher) pair most of a college's figures are on, once per college, and a
  `figure_statuses` entry exists only for a field with something of its own to
  say — a status sentence, a year gap, or a tier or publisher off the default —
  repeating a provenance key only where THAT key differs, so each fact resolves
  as "this entry's own value, else the default's". The tier code and its
  sentence ride in one `SpokenAssurance` value and cannot split.
  `residency.by_college` and `blended_figure_basis.by_college` are emitted only
  where a school is not fully described by the sentence above it (the
  `CHOSEN_BY_COLLEGE_KEY` rule, with a uniformity clause as the reachability
  net), and the breakdown NAMES the top-level tuition key its totals include
  (`tuition_field_in_totals`) instead of repeating the figure in all three
  arrangement objects. MEASURED: 4,980 -> 1,864 bytes per college on the
  `Six Statuses U` fixture (-62.6%), and 3,365 -> 999 on a plain college
  (-70.3%), asserted as a byte budget in `CollegeCostChatToolTest`. The >= 60%
  criterion IS met, but only after a design amendment approved mid-run:
  all-three-or-none exception entries measured 44.7% and missed it, because the
  research report's reconstruction assumed ONE distinct (tier, publisher) pair
  per college while the real corpus carries THREE — the Scorecard both relays
  IPEDS cells and originates two federal administrative records. The 5,366-byte
  figure in `research/coach-verbosity.md` was reconstructed, never measured;
  recorded as a spec defect against /chart. No DDL, no migration, no prompt
  version; the nine byte-frozen sentences are unchanged and
  `prompts/coach-system-prompt.md` was not touched.

  concise/04/cite-once-per-block LANDED as RFC 190 (main@4a4bf350 + 7de43df6,
  2026-09-11) — the borrowing block is attributed ONCE, in a header, and the
  figures follow. `BorrowingWire.listSentences` became
  `blockOf(coverage): BorrowingBlock(header, sentences)`, so the sentences are
  UNREACHABLE without the attribution they are claimed under — "the block cannot
  render without its header" is a property of the type, not a convention. The
  header is built from `CdsCitation.citedAs` and names the school, its cycle and
  its Common Data Set once; the seven sentence builders dropped the "<College>
  reports that … graduated in 2024-25" frame and kept the loan type, the cohort,
  the figure and the ours-vs-theirs voice. The page's trailing
  `Source: <cited as>.` line is gone — it printed the same `citedAs` the header
  now opens with, and no url (verified: `CostReportPage.kt` renders no `href` at
  all), so nothing became unreachable (D1). The merit block states its citation
  before its figures for the same reason. ATTRIBUTION WENT UP, not down: on the
  mixed-source debt section the federal median debt used to name no publisher,
  and now names its own, derived from the cell the figure came from
  (`statusNoteFor(...).cell.source` through `MoneySourceCopy`), because the
  block header scopes only the school's own filing; a test asserts both
  publishers are present and neither stands for the other, and RFC 175 D9's
  no-sum/no-comparison list stays green. This RFC SUPERSEDES RFC 175 D10's
  per-sentence rule for the borrowing block — its reason is unchanged (an
  unaudited self-report must never read as an administrative record), only the
  unit the attribution rides in — and the D10 test is REPLACED, not deleted, by
  `every borrowing figure is covered by the block's one attribution`. MEASURED:
  1,530 -> 1,450 words on a three-school render (-5.2%), ratcheted in the
  existing `CostReportPageTest.WORD_CEILING`; no second counter. The slice's
  `<= 1,230` criterion is NOT met — it came off the superseded 1,793 chain
  (SD1-SD4) and the real removable repetition here was 80 words. The real number
  is reported rather than an assertion weakened. Gate: `bin/test` 3,081
  executed, 0 failures, plus the pre-commit hook twice (the first land was
  refused at the fast-forward because `main` advanced, so the hook re-ran on the
  tree that actually landed). No DDL, no migration, no prompt version;
  `prompts/coach-system-prompt.md` was not touched and the nine byte-frozen
  sentences are unchanged.

  concise/05/tiers-grouped-not-repeated LANDED as RFC 191 (main@bb581547 +
  e3d9257a, 2026-09-11) — an assurance tier is said ONCE for the SET of figures
  it covers (gate-1 D3). The administrative-record sentence was printed three
  times on a three-school report, once inside every school's debt paragraph, and
  is now printed once, in a page-level `report-kinds` block above the sources
  section: a derived scope naming the figures it covers, then the sentence
  verbatim from `AssuranceTierCopy`. The covered set is DERIVED at the site that
  renders it (`proseFiguresOf`), never a hand list. Two exclusions, both RFC
  189's own: a figure with NO NUMBER is in no group and keeps its own status and
  tier sentence in its own cell (`blankFor` untouched, so nothing became
  unreachable — D1), and the at-home `$0` housing line carries no published cell
  at all, so OUR assumption can never be swept under a publisher's tier (RFC 166
  §7), asserted by test. THE REVIEW CATCH, and the thing to carry forward: a
  bare group label computed over the COVERED set alone OVER-REACHES, because
  ambiguity is not a property of what the block groups — it is a property of
  what the PAGE claims. One school's debt shown on a federal tier and another's
  withheld on a survey tier is exactly the case where a bare header speaks for a
  figure it does not cover. The correct shape is
  `groupsOf(figures, spokenElsewhere)`, measuring ambiguity over everything the
  page states a tier for; `spokenElsewhere` is never rendered and only widens
  the set of fields that must name their schools. The synthetic `debtPublisher`
  fixture knob is justified precisely BECAUSE no real publisher pairs a survey
  tier with `GRAD_DEBT_MDN` — an untestable case is not the same as an
  impossible one, and a page-level guarantee tested only in a unit suite is one
  the renderer never has to keep. Also landed: `CostField.spokenLabel`
  (exhaustive — the page authors no money copy), a `require` refusing one
  (college, field) pair on two tiers, and one DESCRIPTION sentence extending RFC
  189's once-per-default rule to the EXCEPTION group, asserted by test.
  MEASURED: 1,450 -> 1,401 words on a three-school render, ratcheted in the
  existing `CostReportPageTest.WORD_CEILING` (1450 -> 1401); no second counter.
  The slice's `<= 1,170` criterion is NOT met, missed by 231 words — it came off
  the superseded 1,793 chain (SD1, SD2, SD5) and the removable repetition here
  was 60 words against an 11-word block that names what it covers. The real
  number is reported rather than an assertion weakened. OPEN ITEM recorded in
  the RFC: the page speaks a tier for ONE of the eleven figures a school
  resolves (11/10/10 shown per school; mandatory_survey 10/9/9,
  administrative_record 1/1/1), unchanged by RFC 190 and unchanged here; closing
  that gap costs about +55 words said ONCE, which is not this brief's kind of
  cost, so nothing landed here forecloses it. Gate: the landing `bin/test check`
  through `bin/pre-commit` passed, exit 0, 3m18s, verified tree cc921989; an
  earlier full `bin/test` on the pre-review-fix tree ran 3,110 executed, 0
  failures. No DDL, no migration, no prompt version;
  `prompts/coach-system-prompt.md` was not touched and the nine byte-frozen
  sentences are unchanged.

  concise/08/the-link-we-promised LANDED as RFC 196 (main@44b4291c + 00a3a8ef,
  2026-09-12) — the Family Cost Report now links each self-reported figure to
  the college's own filing (D15, Ian: "We should link to our sources"). The page
  rendered ZERO `href`s while `AssuranceTierCopy`'s byte-frozen
  voluntary-self-report sentence promised "We link to the college's own file so
  you can see it". Two shapes were in the way: merit held the whole
  `CdsCitation` and printed only `citedAs`, and borrowing's citation was
  FLATTENED to a prose header in the service before the page could see it — the
  RFC 177 shape, a fact read from the store and dropped a layer later.
  `BorrowingBlock` now carries the `CdsCitation` itself; RFC 190's guarantee is
  kept and tightened, because the attribution the figures are unreachable
  without is a typed citation instead of an opaque string, and `blockOf` is
  still the only way to reach the sentences. Link target is `source_url`, link
  TEXT is `citedAs` — the string the page already printed — so the page gained
  links and ZERO words. `archive_url` stays on the wire and off the page. THE
  ACCEPTANCE CRITERION AS WRITTEN WAS VACUOUS, and this is the finding to carry
  forward: "wherever the VOLUNTARY_SELF_REPORT sentence can be rendered, a link
  must be rendered too" could not have failed, because that tier is reachable
  only from `MoneySource.COMMON_DATA_SET` and no `CostField` is CDS-filled — the
  sentence cannot render on this page at all. The real broken promise was one
  level down, in the two CDS attributions the page does print. The criterion is
  kept as a TEST stated as an implication (sentence => link), vacuous today and
  live the day a CDS figure reaches the page. That is SD9, and the first of this
  brief's pre-measurement claims that was wrong about BEHAVIOUR rather than
  about a number. Ian, at the gate: "I wrote a criterion against the surface I
  imagined instead of the one that exists." SECURITY, on Ian's requirement at
  the gate: `href` is the one attribute where escaping does not save a
  logged-out parent, so a `source_url` that is not http(s) FAULTS with a located
  `UnlinkableSourceUrlException` naming the college and the cycle, and reaches a
  family as the route's existing branded 503 — never a silent drop, never a
  rendered link. "The alternative to this check is not a missing link, it is an
  executable one." The predicate (`isWebUrl`) lives in `:common`; the located
  fault stays on the domain type. The claim it fails closed on is ENFORCED, not
  asserted: `CdsSeedLoaderTest` walks all 5,048 committed `source_url`s.
  `rel="noreferrer"` on every anchor, beside the route's existing
  `Referrer-Policy: no-referrer`. THE DOOR: a parent opens the share link and
  clicks the school's name under "Money this school gives for something other
  than financial need" or above the borrowing figures, and lands on that
  school's own Common Data Set file. MEASURED: 1,401 -> 1,404 words, ratcheted
  deliberately in the existing `CostReportPageTest.WORD_CEILING`. The three are
  NOT words — the link text is a string the page already printed; the merit
  line's sentence-final `.` now follows a `</a>` and the tag-strip counts it as
  its own token. The instrument was left alone rather than adjusted to flatter
  the number. Review found four real defects in my own work, all fixed before
  landing: the RFC claimed the page "has no access to" the raw url (false — a
  public property, so the rule is a convention and a source sweep now enforces
  it); the fail-closed argument rested on an unenforced corpus assumption (now
  the seed guard); `BorrowingBlock.headerTail` was a rendering parameter on a
  shared wire record (dropped); and the seed guard hand-rolled `split(",")` in
  the module whose loader uses Apache Commons CSV, one quoted comma away from
  validating the wrong column while staying green. Gate: the landing
  `bin/test check` through `bin/pre-commit` passed, exit 0, 3m29s, verified tree
  75f4a692 — 3,141 executed, 0 failures, 0 errors. No DDL, no migration, no
  seeded row, no prompt version; `prompts/coach-system-prompt.md` was not
  touched and the nine byte-frozen sentences are unchanged. OPEN ITEM: link rot.
  5,048 urls, the longest 1,744 characters, pointing at colleges' own PDFs; some
  are already dead. We hold `archive_url` and deliberately do not render it,
  which is right for the promise as worded — but a parent meeting a 404 is a
  real outcome nobody has decided about.

## The question

Ian, 2026-09-11:

> The coach and the public-web HTML parent report both duplicate a lot of data.
> E.g. tuition is the same for each of the living situations (on-campus,
> off-campus, at home), but it's listed three times. It constantly states
> "\<college\> \<school-year\> Common Data Set reports ...". These reports
> should be much more concise. And the coach should be more concise in its
> delivery of information. I do not want to lose information, just give it more
> efficiently.

So the brief is not "say less". It is **say the same thing in fewer words**.
Every figure, hedge, status, assurance tier and attribution that briefs 0003,
0005, 0006 and 0008 fought to make honest stays reachable; what goes is the
**restatement** of it. The constraint that makes this hard is exactly why it is
worth a brief: eight briefs of honesty work landed as _per-figure sentences_,
and the cheapest way to compress a per-figure sentence is to delete the honesty.
That is the failure mode this brief exists to avoid.

### Why now

The surfaces are finished enough to read as a whole. Brief 0008 closed on
2026-09-11 (RFC 183) and brief 0006 is closed but for one deferred slice, so the
Family Cost Report and the `college_cost_profile` tool now carry their full
intended payload for the first time — publisher (RFC 177), assurance tier (RFC
179), cited year (RFC 183), borrowing (RFC 175), tuition tier (RFC 169). Each
arrived as a correct local decision. Read together they are a wall of text.
Nobody has yet spent a run on the _composition_.

## What the repo does today

Grepped, not guessed (2026-09-11).

| Surface             | Where                                                                                                 | Shape today                                                                                                                                                                                                           |
| ------------------- | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Parent report page  | `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` (1127 lines)                    | `summaryTable` -> per-school `schoolSection` -> `tuitionTierLine`, `priceTable` (`tuitionRow` + `componentRows` + `totalRow` + `wholeSchoolRow`), `meritBlock`, `debtBlock` + `borrowingParagraphs`, `sourcesSection` |
| Coach money tool    | `service/.../costs/CollegeCostChatTool.kt` (1913 lines), `CollegeCostService.kt` (2248 lines)         | JSON payload with per-figure `figure_statuses[]`, each carrying status + `assurance` + `assurance_statement` (RFC 179)                                                                                                |
| Coach voice         | coach prompt seed, currently **v26**                                                                  | prose rules accumulated across RFCs 175 / 177 / 179 / 181 / 183                                                                                                                                                       |
| Living arrangements | `service/.../costs/CostBreakdown.kt` `ARRANGEMENT_COMPONENTS`                                         | three arrangements, each a list of **housing+food, books, other** — note **tuition is NOT in this map**; the repeat comes from `CostReportPage.tuitionRow` spanning the arrangement columns                           |
| CDS attribution     | `service/.../costs/BorrowingWire.kt` (~15 sentence builders), `service/.../admissions/CdsCitation.kt` | every sentence is independently self-attributing: `"${source.collegeName} reports ..."`, `CdsCitation.cycleLabel(source.sourceYear)`                                                                                  |
| Publisher naming    | `service/.../costs/canonical/MoneySourceCopy.kt`, `FigureStatusCopy`                                  | one seam, one English name per publisher (RFC 177)                                                                                                                                                                    |
| Grouped sourcing    | `CostReportPage.sourcesSection` + `moneyAttributionSentence`                                          | a "Sources and what this is not" section **already exists** — evidence that a grouped-citation home is available and under-used                                                                                       |

Two facts from that table shape the whole brief:

1. The tuition repeat is a **render** decision, not a data-model one. Tuition is
   already school-wide in the domain; only `priceTable` prints it once per
   arrangement column.
2. A grouped sources section **already exists on the page**. So "cite once, at
   the bottom" is not a new invention we have to justify from scratch — it is
   finishing a shape the page already started.

## Candidates

The bet is not _whether_ to compress; Ian has said to. The bet is **where the
first slices go**, and how far the citation rule is allowed to move.

| #  | Candidate                                                                                                                                                           | What it buys                                                                                                          | Existing foundation                                                                                | Risk                                                                                                                             |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| C1 | **Cite once, not per figure** — one attribution per source per section/answer, with the per-figure detail still reachable                                           | The largest single word saving on both surfaces; kills the "\<college\> \<year\> Common Data Set reports..." drumbeat | `sourcesSection` + `moneyAttributionSentence` already group; `MoneySourceCopy` is already one seam | Highest honesty risk. RFC 177/179 attach the publisher and tier to the FIGURE on purpose. Needs a rule for mixed-source sections |
| C2 | **Hoist the school-wide figures out of the per-arrangement table** — tuition (and any other identical row) stated once above the columns                            | Ian's named example; pure render change; no wire, no DDL                                                              | `schoolWideTuition`, `tuitionTierLine`, `wholeSchoolRow` already exist                             | Low. Must keep the tuition-tier label (RFC 169) and the withheld/blank arms (RFC 183)                                            |
| C3 | **A coach concision contract** — prompt rules (front-load the number, table for parallel figures, hedge only where it changes the decision) at a new prompt version | Fixes delivery without touching any wire type; instantly rollback-able                                                | Prompt-version + rollback convention is established (v26, rollback = previous version)             | Prompt-only changes are soft; needs a real evaluation, not vibes                                                                 |
| C4 | **Slim the tool payload** — stop shipping identical per-figure boilerplate to the model; group statuses; drop restated tuition per residency/arrangement            | Fewer tokens in, less for the model to parrot back; often the real cause of C3's symptom                              | `FigureGroup.kt`, `figure_statuses[]` already structured                                           | Changing a tool contract touches tests broadly; must not remove a status a family needs                                          |
| C5 | **Progressive disclosure on the page** — hedges and provenance behind a details/expander rather than inline prose                                                   | Big perceived-length win with zero information loss by construction                                                   | Static HTML page, no JS framework needed (`<details>`)                                             | Accessibility + print/share behaviour; a hedge nobody opens is arguably lost                                                     |
| C6 | **A brand voice rule for money copy** — one written standard both surfaces are checked against                                                                      | Stops the drift recurring after this brief                                                                            | `PRODUCT.md`, the `principle` corpus                                                               | Doc-only; buys nothing on its own                                                                                                |

## Success criteria for the decision

Gate 1 is answered well if, after it, we can say:

1. **A measured target exists.** Not "shorter" — a number, e.g. words per school
   in the report and per money answer in chat, measured before and after.
2. **The information-loss rule is written down**, so every downstream slice can
   be checked against it rather than re-arguing it. Draft: _no figure, status,
   assurance tier, cited year or publisher may become unreachable; a restatement
   of one may be removed._
3. **The citation rule is decided explicitly** (C1) — per figure, per section,
   or per answer — because every other slice inherits it.
4. **The order is decided**: which surface first, page or coach.

## Open questions carried into gate 1

- Is "reachable behind a disclosure" the same as "not lost"? (C5 stands or falls
  on this.)
- Does the report keep speaking a CDS figure as the **school's own claim** in
  the sentence, if the claim-hood moves to a grouped line? (RFC 175's rule.)
- Do we accept a **table** as a legitimate coach answer format in chat?

---

## Research synthesis (2026-09-11)

Three parallel reports, all cited. `report-verbosity` **rendered the real page**
in a throwaway worktree (no repo file changed); `coach-verbosity` measured the
prompt and tool description and reconstructed payloads from real strings;
`concision-craft` fetched every external source.

### The size of the problem, measured

| Surface       | Measure                                         | Value                                                                         |
| ------------- | ----------------------------------------------- | ----------------------------------------------------------------------------- |
| Parent report | words, 1 school                                 | **798** (9,008 bytes)                                                         |
| Parent report | words, 3 schools                                | **1,793** (23,260 bytes) — ~404 per extra school                              |
| Parent report | exact repeated blocks, 3 schools                | **502 words = 28.0%**                                                         |
| Parent report | debt + borrowing block                          | **504 of 1,793 words = 28%**                                                  |
| Coach payload | `figure_statuses[]`, one college, realistic mix | **5,366 bytes**; ~16,100 for three schools (~4,000 tokens)                    |
| Coach payload | of which byte-identical assurance/source triple | **75%**, carrying **1** distinct assurance sentence                           |
| Coach         | tool DESCRIPTION                                | **16,383 bytes / 2,660 words**, largely restating the prompt                  |
| Coach         | system prompt v26                               | **25,201 bytes / 4,523 words / 158 lines**, ~158 imperative "say X" sentences |

### Ian's two examples, confirmed — and both are bigger than stated

**(a) Tuition three times.** Confirmed and structural: `ArrangementCost` holds a
`tuitionLine` **per arrangement**, so `priceTable` prints the identical figure
in all three columns (`$12,000 | $12,000 | $12,000`). **Books and supplies has
the exact same shape and was not in the brief** — add it. Counting the summary
table, tuition is printed **four** times per school.

**(b) The CDS drumbeat.** Confirmed and larger: in the 3-school body,
`"2024-25"` appears **21** times, `"reports that"` **12** times,
`"who graduated in 2024-25"` **12** times, each school's own name 12-13 times.
One school's borrowing block is five paragraphs that each re-state the school
name and the cycle. Cause: every `BorrowingWire` sentence re-interpolates
`collegeName` + `cycleLabel`, because **RFC 175 D10 requires per-sentence
attribution and a test asserts it.** That is the one compression that needs a
real product decision, not a render fix.

### Two defects found on the way

1. **A public-school sentence prints under private schools.**
   `SCHOOL_BLENDED_HINT` (`CostReportPage.kt:228-231`) is unconditional and ends
   with `BLENDED_IN_STATE_CLAUSE` — "At a public school it, and the likely price
   after a financial aid offer, are figures for students paying in-state
   tuition." In the real render it appears under two `PrivateNonprofit` schools.
   Compressing it is also a correctness fix.
2. **The residency sentence prints twice on a one-school report** — the
   commonest report shape (`SingleSchoolBasis.statements` +
   `CostReportPage.kt:509`).

### What the outside world does

Every serious surface converges on one shape (`concision-craft` §2.8): headline
figure -> table of parallel figures -> ≤10-word inline qualifiers -> one caveat
sentence only where it changes the reading -> **one source line per block** ->
definitions and provenance off-surface. Niche prints "Data source: IPEDS by U.S.
Dept. of Education" once per block; the Scorecard keeps 6,337 words of glossary
off the page; CFPB keeps a single methodology link.

Three findings decide our defaults:

- **Concision is measurably better, not merely tidier.** NN/g's Morkes &
  Nielsen: concise **58% better** measured usability, scannable **47% better**,
  combined **124% better**. Users read at most **28%** of the words on a page.
- **Verbosity is a correctness risk, not just a style one.** Liu et al., EMNLP
  2023 Findings (arXiv:2304.09848): in generative search engines only **51.5% of
  generated sentences are fully supported by citations**, producing a "facade of
  trustworthiness". More sentences means more unsupported sentences. Citation
  density is not trust.
- **The trust evidence for per-figure attribution does not exist.** The
  researcher searched OpenAlex and found **no study** comparing grouped versus
  per-figure source attribution in a data UI. What is supported: sourcing must
  be named, authoritative, present and findable. "One source line per block" is
  best-practice **convergence**, not a proven trust-neutral result. Treat it as
  a judgement call we make with eyes open — and A/B it if we want certainty.

### Where compression is free, and where it costs a guarantee

**Free** (no RFC guarantee touched; the guarantee is that the thing is _said_,
never that it is said N times):

- hoisting school-wide rows out of the arrangement grid (O1);
- hoisting the constant hints to page level + making the in-state clause
  conditional on `CollegeControl.Public` (O3) — also fixes defect 1;
- printing the residency sentence once on a one-school report (O7) — defect 2;
- emitting `by_college` statements **only where a school differs** from the
  call-level basis — the precedent already exists in the same file
  (`CHOSEN_BY_COLLEGE_KEY`, `CollegeCostChatTool.kt:266-270`);
- hoisting `assurance` + `source` to a per-college default and emitting an entry
  only for exceptions: **5,366 -> ~1,400 bytes, a ~4x cut**, no fact dropped;
- deleting the tool description's restatements of prompt rules — **two copies of
  one rule in one context window**, ~16 KB -> ~3 KB;
- deleting the prompt's own internal restatements: the money vocabulary is
  written out at least **four** times, "loans are not a price reduction"
  **five** times — a product of the append-only prompt convention, one RFC one
  paragraph, not of the rules themselves.

**Costs a decision** (an RFC guarantee is worded per-figure):

- **RFC 175 D10** — every borrowing sentence must attribute the figure to the
  school, with a test asserting it. A grouped header ("Riverside State
  University's 2024-25 Common Data Set reports:") is arguably still attribution,
  but it is an RFC-level change. This is the 28%-of-the-page block.
- **RFC 179** — "say it beside the figure". Good news: RFC 179 minted **no
  prompt version**; this instruction lives only in the tool description, which
  is ordinary code. Bad news: it is deliberate.
- **RFC 177** — "attribute each cost figure to the source the tool names beside
  it" (prompt `:32`). But RFC 177's guarantee is **correctness of attribution,
  not cardinality**, and RFC 177 D5 already ships a once-per-answer roll-up
  sentence. The payload already carries a top-level `source` set, emitted
  absent-never-empty. So "name the set once; name a figure's publisher only
  where it differs" is safe _and already supported by the data on the wire_.

**Must not be compressed** (named explicitly so no slice touches them):

- the `OverallAverage` note — "an overall average across all families, not a
  figure for this family". `CostReportPage.kt:100-118` argues that a number
  printed beside a family's name is read as theirs whatever the note says.
- the `report-sources` "these are not an offer" paragraph.
- the six `FigureStatusCopy` status sentences and the three `AssuranceTierCopy`
  tier sentences are **byte-frozen** — `SystemPromptCatalogTest` pins them
  against the immutable v19 row. Compression removes _repetitions of_ these
  sentences; it never rewords one.
- `data-label` attributes (1,737 bytes) are a real mobile-layout requirement,
  not waste.

### Ranked candidates after research

| Rank | Bet                                                 | `report-verbosity`                                                    | `coach-verbosity`                     | `concision-craft`                           | Verdict                                             |
| ---- | --------------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------- | ------------------------------------------- | --------------------------------------------------- |
| 1    | **C2 Hoist school-wide rows + fix the two defects** | O1/O3/O7, low risk, answers Ian's example (a), fixes a wrong sentence | —                                     | R3 "units in the header, once"              | **Do first.** Free, visible, correctness win        |
| 2    | **C4 Slim the tool payload**                        | —                                                                     | O1/O4: ~4x cut, precedent in-file     | fewer tokens -> fewer unsupported sentences | **Do first.** Parallel to rank 1, different files   |
| 3    | **C6->prompt/description de-duplication**           | —                                                                     | O5: 16 KB -> 3 KB, one rule one home  | —                                           | **Do first.** Pure duplication                      |
| 4    | **C1 Cite once per block**                          | O2, HIGH risk under RFC 175 D10                                       | O6 safe restricted form under RFC 177 | R4, convergence not proof                   | **Needs D2/D3.** Biggest single win                 |
| 5    | **C3 Coach concision contract**                     | —                                                                     | O7/O9 behavioural, needs eval         | R1/R2 inverted pyramid + tables             | **After the payload slims**                         |
| 6    | **C5 Progressive disclosure**                       | O6, no `<details>` exists in `public-web` today                       | —                                     | CFPB: hide non-critical only                | **Needs D5.** Zero information loss by construction |
| 7    | **C6 voice rule as a doc**                          | —                                                                     | —                                     | R1-R5 are already testable                  | **Fold into slices**, not its own slice             |

---

## Gate 1 — prioritisation

Defaults are pre-chosen. Approving costs one word; amending costs one line.

**D1 — The information-loss rule (the rule every downstream slice is checked
against).** _Default:_ **"No figure, status, assurance tier, cited year,
publisher or unicoach-owned assumption may become unreachable on a surface. A
**restatement** of one may be removed. Rewording a byte-frozen sentence is not
compression."**

**D2 — The citation rule.** Per figure / per block / per answer? _Default:_
**per block.** On the page, one attribution line per data block (school section,
merit block, borrowing block). In chat, name the payload's own top-level
`source` set **once per answer**, and name a figure's publisher again **only
where it differs from that set**. Rationale: RFC 177's guarantee is correctness,
not cardinality; RFC 177 D5 already ships a once-per-answer roll-up; the wire
already carries the set. Accepting this **commits us to a new RFC that
supersedes RFC 175 D10's per-sentence rule** for the borrowing block.

**D3 — The assurance-tier rule (RFC 179 "say it beside the figure").**
_Default:_ **group by tier, never by figure.** Every number stays covered by
exactly one tier sentence, said verbatim, never re-worded, ranked or scored —
"these figures come from the college's required federal filing: <tier sentence>;
the median debt figure is different: <tier sentence>". This lives in the tool
description (ordinary code, no prompt mint), so it is cheap to try and cheap to
revert.

**D4 — Measured targets, so "concise" is falsifiable.** _Default:_ parent report
**≤ 400 words per school on a 3-school report** (from 404 excess-inclusive
today, i.e. roughly **halve** the 1,793 to **≤ 900**); `figure_statuses[]`
payload **≥ 60% smaller** per college; tool description **≤ 4 KB**. Each slice
asserts its own number in a test, not in prose.

**D5 — Is "behind a disclosure" the same as "not lost"?** _Default:_ **yes for
definitions, provenance and standing hedges; no for anything band-specific,
anything that changes a number, the `OverallAverage` note, and the "this is not
an offer" paragraph.** This follows CFPB's own limit: content behind a tooltip
or expander must not be essential to the task.

**D6 — Order and parallelism.** _Default:_ **Wave 1 in parallel** — (i) the page
render hoists + both defect fixes, (ii) the tool-payload slimming, (iii)
prompt/description de-duplication. Different files, no shared contract. **Wave
2** — the block-citation RFC (page and coach together, one rule). **Wave 3** —
coach answer shape (front-load, tables) and progressive disclosure.

**D7 — Is a table an acceptable coach answer format in chat?** _Default:_
**yes.** Three or more parallel figures of the same kind render as a table, one
row each; units live in the header, once. (`concision-craft` R2/R3.)

**D8 — Does this become a standing rule, inherited by future briefs?**
_Default:_ **yes — standing.** "Say it once: every surface states a fact, a
source and a hedge once per block; repetition is a defect, not a safety margin.
Compression never removes reachability." Marked `standing` so briefs after 0009
inherit it without re-asking.
