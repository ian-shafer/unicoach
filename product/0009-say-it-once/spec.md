# 0009 "Say it once" — spec and slices

Brief handle: **`concise`**. Gate 1 passed 2026-09-11 (Ian: "approve", all eight
defaults). This file carries DISCOVER's corrections, the slices, and gate 2.

## DISCOVER — what is actually true in the tree

Measured, not assumed. `report-verbosity` rendered the real page in a throwaway
worktree; `coach-verbosity` measured the prompt and description and
reconstructed payloads from real strings. Corrections the slices depend on:

1. **Tuition is already school-wide in the domain.** `ARRANGEMENT_COMPONENTS`
   (`CostBreakdown.kt:44-66`) holds only housing-and-food, books, and other
   expenses. The repeat is purely `CostReportPage.tuitionRow` printing one
   figure across the arrangement columns. **No wire change is needed to fix
   Ian's example (a).**
2. **Books and supplies has the identical shape** (`$1,200 | $1,200 | $1,200`)
   and was not in Ian's note. It is in scope.
3. **A grouped sources section already exists** — `sourcesSection` +
   `moneyAttributionSentence` (`CostReportPage.kt:1081-1130`). "Cite once" is
   finishing a shape the page started, not inventing one.
4. **`wholeSchoolRow` already exists** (`CostReportPage.kt:757`) — the hoist has
   a render primitive waiting for it.
5. **RFC 179 minted no prompt version.** Its "say it beside the figure"
   instruction lives only in the tool DESCRIPTION
   (`CollegeCostChatTool.kt:1736-1741`), which is ordinary code. D3 is therefore
   a code change with a code revert, **not** a prompt mint.
6. **RFC 177's roll-up precedent exists.** RFC 177 D5
   (`rfc/177-one-hedge-seam.md:255-258`) already ships a once-per-answer "The
   cost, price and federal debt figures come from …" sentence. D2's chat half is
   consistent with a decision already committed.
7. **The payload already carries the source SET.** Top-level `SOURCE_KEY`,
   emitted absent-never-empty (`CollegeCostChatTool.kt:90-94, 1712`). "Name the
   set once, name a figure only where it differs" needs **no new wire data**.
8. **The precedent for conditional per-college keys is in the same file.**
   `CHOSEN_BY_COLLEGE_KEY` is emitted only when schools differ
   (`CollegeCostChatTool.kt:266-270`), with a comment saying a second per-school
   list "would invite the coach to narrate a difference there is none of".
   `residency.by_college` and `blended_figure_basis.by_college` are written
   unconditionally (`:174`) and should follow that precedent.
9. **Nine sentences are byte-frozen.** Six in `FigureStatusCopy`, three in
   `AssuranceTierCopy`; `SystemPromptCatalogTest` pins them against the
   immutable **v19** prompt row. ~89 of 158 prompt lines are pinned as string
   literals, and a `CoachParagraph` opener test fails when a paragraph's
   **first** sentence is reworded. **Consequence for every slice: compress by
   deleting whole redundant sentences, never by rewriting one, and never touch a
   paragraph's opening sentence.**
10. **No `<details>` element exists anywhere in `public-web/src/main`**
    (grepped, zero hits). `concise/07` is new chrome, not a reuse.
11. **No slice in this brief adds a database table, column, migration or seeded
    row.** Non-negotiable 6 has nothing to gate here. The only versioned
    artifact is the coach prompt, whose rollback is an env-var flip.

### Standing bars every slice must clear

- **D1 (gate 1):** no figure, status, tier, cited year, publisher or
  unicoach-owned assumption may become **unreachable**. A restatement may go.
  Rewording a byte-frozen sentence is not compression.
- **D5 (gate 1):** never hide behind a disclosure: the `OverallAverage` note
  ("an overall average across all families, not a figure for this family"), the
  `report-sources` "this is not an offer" paragraph, or anything band-specific.
- **RFC 149 D-C:** no total summed from partial parts. **RFC 175 D9:** the two
  debt publishers are never summed or compared. **RFC 179 D10:** no number,
  score or rating of how solid a figure is. **RFC 166 §6:** our gap is said as
  ours, never as the school's silence.
- **Value before ask:** no slice here adds a question, a step or a gate. Each
  one removes words from a surface a family already reached. Nothing degrades on
  decline because there is nothing to decline.

---

## The word budget, honestly

Gate-1 **D4** set "≤ 900 words on a 3-school report". Slicing the work forced me
to add up what removing **every identified repeat** actually saves:

| Saving                                                                              |   Words |
| ----------------------------------------------------------------------------------- | ------: |
| Blended hint hoisted to page level                                                  |     102 |
| Income-band note said once (`BandSpecific` only)                                    |      72 |
| Misc repeated one-liners (tier line, no-year, assumption, headers, citation suffix) |      91 |
| Merit block repeats (heading, hedge, cohort note)                                   |      90 |
| Residency sentence printed once                                                     |      25 |
| Assurance tier grouped by tier                                                      |      60 |
| Borrowing frame grouped per block                                                   |     200 |
| Tuition + books cells hoisted (12 cells)                                            |      24 |
| **Total**                                                                           | **664** |

**1,793 − 664 = 1,129.** So D4's 900 is **229 words short of reachable** by
removing repetition alone. The only way to 900 is converting the merit and debt
**prose into tables** (option O5), which `report-verbosity` rates **high risk**
under RFC 175 D9 — a shared table makes two un-summable publishers look
summable.

Gate decisions are immutable, so D4 is not edited. **D9 below is the new
decision** that supersedes its number, with this table as its evidence.

---

## Wave board

| Wave | Slices                                   | Why they are safe together                                                                                                                                                                                     |
| ---- | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | `concise/01`, `concise/02`, `concise/03` | Disjoint trees: `public-web` render / chat-tool payload builders / tool description + prompt file. `02` and `03` both touch `CollegeCostChatTool.kt` in different regions — CONFLICTS, a rebase, not an order. |
| 2    | `concise/04`, `concise/05`               | The two judgement calls (D2, D3), each with a clean baseline to measure against.                                                                                                                               |
| 3    | `concise/06`, `concise/07`               | Answer shape and disclosure, once the payload is already thin.                                                                                                                                                 |

---

## `concise/01/the-page-says-it-once`

**Needs:** — (nothing: every change is inside `CostReportPage.kt`'s own render
functions; no other slice's output is consumed and no wire type changes.)

**What to build and why.** A parent opening the shared Family Cost Report reads
1,793 words for three schools, 502 of them exact repeats. This slice deletes the
repetition that lives purely in the renderer, and fixes two defects found while
measuring it. Nothing a family can learn today becomes unlearnable.

**Foundations.** `wholeSchoolRow` (`CostReportPage.kt:757`) and
`schoolWideTuition` (`:483`) already implement the "one figure for the whole
school" shape for the summary table. `ARRANGEMENT_COMPONENTS`
(`CostBreakdown.kt:44-66`) is the truth about which fields can vary by
arrangement.

**Decided here.**

- Hoist **tuition and fees** and **books and supplies** out of the arrangement
  grid, printed once per school above the columns, still counted in each
  arrangement total.
- Hoist `SCHOOL_BLENDED_HINT` (`:228-231`) to **page level**, said once above
  the school sections.
- **Defect 1:** emit `BLENDED_IN_STATE_CLAUSE` only where
  `cost.control is CollegeControl.Public`. It currently prints under private
  schools, which is simply false there.
- **Defect 2:** on a one-school report, drop the duplicate residency sentence at
  `:509`; `SingleSchoolBasis` already says it. On a 2+-school report the
  per-school line **stays** — there it is the only place a specific school's
  basis is stated.
- Say the **`BandSpecific`** income-band note once (summary table or column
  header), not in every net-price cell.

**Left to /ship's design phase.** Where exactly the hoisted rows sit relative to
the table (above it, or as a separate two-row block); the markup and CSS for the
page-level hint; the exact phrasing of the once-said band marker.

**Acceptance criteria.**

- The "one figure for the whole school" test is **derived**, not hard-coded per
  role: a row is hoisted when its values across arrangements are identical
  (`distinct().size == 1`), so a future fourth arrangement with its own books
  field cannot silently collapse two different numbers into one.
- The **`OverallAverage`** note is untouched and still prints beside its figure.
  Only the `BandSpecific` note moves. (D5; `CostReportPage.kt:100-118`.)
- Every arrangement total still equals its parts, and a partial total is still
  refused (RFC 149 D-C).
- Labelled blanks, withheld cells and year-gap arms render exactly as today.
- A test asserts the **rendered word count** of the 3-school fixture page and
  fails if it rises. Target for this slice alone: **≤ 1,480 words** (from
  1,793).
- A test asserts `BLENDED_IN_STATE_CLAUSE` is **absent** from a
  `PrivateNonprofit` school's section and **present** for a `Public` one.
- A test asserts the residency sentence appears **exactly once** on a one-school
  report and once per school on a three-school report.
- No wire type, no DDL, no prompt version.

**Reachability / the door.** The existing one: the share link a student sends a
parent, opened in a browser. No new door, no new ask.

**First-session test.** A parent opening a three-school report for the first
time sees each school's tuition stated once, not four times, and reads no
sentence about in-state tuition under a private college.

---

## `concise/02/the-payload-says-it-once`

**Needs:**

- CONFLICTS concise/03/one-rule-one-home — both edit `CollegeCostChatTool.kt`,
  in different regions (payload builders vs. the DESCRIPTION constant). A rebase
  risk, not an order.

**What to build and why.** The coach is handed ~5,366 bytes of `figure_statuses`
per college, **75% of it byte-identical**, carrying exactly one distinct
assurance sentence. The coach then recites what it was handed. Slimming the
payload removes the _cue_ for half the verbosity before any prompt rule is
written.

**Foundations.** `figureStatusesOf` (`CollegeCostService.kt:1990+`),
`figureStatusObject` (`CollegeCostChatTool.kt:752-777`), `comparisonBasisObject`
(`:102-130`), and the in-file precedent `CHOSEN_BY_COLLEGE_KEY` (`:266-270`).

**Decided here.**

- Emit provenance **once per college** as a default object (assurance tier +
  source), and emit a `figure_statuses` entry only for a field whose status is
  not plainly reported **or** whose tier/source differs from that default.
- Emit `residency.by_college` and `blended_figure_basis.by_college` **only where
  a school differs** from the call-level basis — the `CHOSEN_BY_COLLEGE_KEY`
  rule, applied to the two keys that ignore it.
- Emit each college's tuition figure **once**, not once per arrangement object.

**Left to /ship's design phase.** The key names and object shape of the default;
whether the three tier sentences ship as a small glossary object or as a code
the description explains.

**Acceptance criteria.**

- **Every figure still resolves to exactly one tier and exactly one publisher**,
  mechanically, from default + exceptions. This is D1: nothing unreachable.
- The default key is **mandatory, never optional** — a reader must never have to
  guess a missing default. An unresolvable figure is a typed fault, not a guess.
- Tests cover the cases compression breaks: a **mixed-publisher** college, a
  **mixed-tier** college, a `not_collected_by_us` field, a `held_academic_year`
  year gap, and a two-school comparison on **different** residency bases (so
  `by_college` must still be emitted).
- A **byte-budget assertion** on a fixed fixture payload: **≥ 60% smaller** per
  college than today's 5,366 bytes. (Gate-1 D4.)
- The nine byte-frozen sentences are **unchanged**; only the number of times
  they are emitted changes.
- No DDL, no prompt version.

**Reachability / the door.** The existing `college_cost_profile` tool — any
family asking the coach what a school costs.

**First-session test.** A family asking about three schools gets the same facts;
the model has ~4,000 fewer tokens of duplicate hedging to parrot.

---

## `concise/03/one-rule-one-home`

**Needs:**

- CONFLICTS concise/02/the-payload-says-it-once — same file
  (`CollegeCostChatTool.kt`), different regions. Rebase, not order.

**What to build and why.** The tool description is **16,383 bytes** that largely
restates the system prompt, so the model is told the same rule twice in one
context window. And the prompt restates itself: the money vocabulary is written
out at least **four** times, "loans are not a price reduction" **five** times —
an artifact of the append-only convention (one RFC, one appended paragraph), not
of the rules. Each rule should exist in exactly one home.

**Foundations.** The prompt-version convention (RFC 181/185): a new seed file
from `prompts/coach-system-prompt.md` via `bin/prompt-seed`, a
`systemPromptVersion` bump in `service.conf`, and a comment naming the rollback
version. `SystemPromptCatalogTest` (2,173 lines) enforces it.

**Decided here.**

- The DESCRIPTION becomes a **schema legend**: what each key means and what
  values it takes. Every _behavioural_ rule ("say it beside the figure", "say
  that sentence rather than writing your own") moves to the prompt, or is
  deleted where the prompt already says it. Target **≤ 4 KB** (from 16,383
  bytes).
- The prompt's **internal** restatements are deleted — whole redundant sentences
  only.

**Left to /ship's design phase.** Which of each duplicated pair survives, and in
which home; the exact new prompt version number.

**Acceptance criteria.**

- **Deletion only, never rewording** (DISCOVER 9). No paragraph's **opening**
  sentence is touched, or the `CoachParagraph` opener test fails. No byte-frozen
  sentence is reworded.
- Every rule that exists today still exists **somewhere** — a named before/after
  list of rules in the RFC, each mapped to its surviving home. This is D1
  applied to instructions rather than to figures.
- Every prior prompt row stays selectable; the rollback version is named in
  `service.conf` and in the RFC.
- Description **≤ 4 KB**, asserted by a test.
- No DDL.

**Reachability / the door.** Invisible to the family by design — this is the
same coach, told each rule once. Its effect is measured in the next two slices.

**First-session test.** Identical answers, fewer instructions competing for the
model's attention.

---

## `concise/04/cite-once-per-block`

**Needs:**

- PREFER concise/01/the-page-says-it-once — product judgement, not technical.
  `01` establishes the measured page baseline this slice is judged against, and
  both edit `CostReportPage.kt`. Overridable by Ian.

**What to build and why.** This is the 28% of the page, and Ian's example (b).
One school's borrowing block is five paragraphs that each restate the school
name and the cycle: `"2024-25"` appears **21** times across three schools,
`"reports that"` **12** times. This slice states the claimant **once per block**
and lets the figures follow.

**This slice supersedes a committed decision.** RFC 175 D10 requires every
borrowing sentence to attribute its figure to the school, with a test asserting
it. Gate-1 **D2** chose per-block attribution instead. Per `rfc/INVARIANTS.md`
the earlier RFC is left as committed and **this slice's RFC carries the
change**, stating plainly that a scoping header is still attribution and why.

**Foundations.** `BorrowingWire.kt:280-425` (the sentence builders and
`listSentences`), `CdsCitation.cycleLabel`, `CostReportPage.borrowingParagraphs`
(**`:1274` after RFC 188** — the `:1037` in this spec's first draft is stale),
`sourcesSection` (**`:1318`**, was `:1081`) as the existing grouped-citation
shape, and RFC 177 D5's existing once-per-answer roll-up sentence. **Re-grep
before relying on any line number here** (SD2).

**Decided here.**

- The report's borrowing block carries **one** attribution header naming the
  school, the cycle and the Common Data Set; the figures beneath it do not
  re-name either.
- In chat, the coach names the payload's top-level `source` **set once per
  answer**, and names a figure's publisher again **only where it differs from
  that set**.
- The **merit** block gets the same treatment, since it shares the shape.

**Left to /ship's design phase.** The exact header wording; whether the figures
render as a list or a table; whether chat and page share one copy seam or two.

**Acceptance criteria.**

- **Every figure is still covered by exactly one attribution**, and the RFC
  states the rule that makes a scoping header sufficient. A figure that escapes
  its block's header is a defect.
- A **mixed-source** block is handled explicitly: where two publishers appear, a
  figure whose publisher differs from the block's header **names its own**. A
  test asserts this — it is the residual risk `coach-verbosity` flagged.
- **RFC 175 D9 holds:** the two debt publishers stay visibly separate, never
  summed and never compared, whatever the layout.
- The **CDS voice survives**: a Common Data Set figure is still spoken as the
  school's own claim, because the header says so for the block. A test asserts
  the block cannot render without its header.
- **RFC 177's correctness rule holds:** the "once" is derived from the payload's
  own source set, never from a default the model picks.
- Page word count: **re-derive the ceiling from a fresh measurement (SD1-SD4).**
  The `≤ 1,230` written here came off the superseded 1,793 chain. The real
  baseline after RFC 188 is **1,530**, and this slice's own allocation in the
  budget table (~200 borrowing frame + ~90 merit repeats) lands it near
  **1,240** — so 1,230 is likely unreachable by about the margin `concise/01`
  hit. **Measure first, then set the ceiling at the real number**, and report
  the miss rather than weakening the assertion.
- **Ratchet the existing `CostReportPageTest.WORD_CEILING` down** (currently
  `1530`, `CostReportPageTest.kt:1557`). Do **not** add a second word counter —
  the counter and the three verbose fixtures already exist.
- `BorrowingWireTest` and `CostReportPageTest.borrowingMarkers` are updated, not
  deleted; the RFC 175 D10 test is replaced by the new rule's test, and the RFC
  says so.
- No DDL. A prompt version only if the chat half needs prompt wording.

**Reachability / the door.** Both existing doors: the shared report link, and
the coach answering a cost question.

**First-session test.** A parent reads the borrowing figures as a titled block
and still knows, without ambiguity, that the school said them about itself.

---

## `concise/05/tiers-grouped-not-repeated`

**Needs:**

- BLOCKS concise/02/the-payload-says-it-once — technical. This slice's rule is
  expressed against the per-college provenance default that `02` introduces;
  landing it first would mean grouping by a key that does not exist yet.

**What to build and why.** Three distinct sentences are emitted up to 17 times
per college, and the tool description tells the coach to say each one **beside
the figure**. Gate-1 **D3**: group by tier, never by figure.

**Foundations.** `AssuranceTierCopy.statementOf` (`:60-76`) — the three
sentences; `AssuranceTierCopy.kt:29-32` — "a tier sentence and a status sentence
are always composed as TWO sentences, in that order… never concatenated"; the
description at `CollegeCostChatTool.kt:1736-1741`. **RFC 179 minted no prompt
version, so this is an ordinary code change with an ordinary revert.**

**Decided here.** Every figure stays covered by exactly one tier sentence, said
**verbatim**, composed status-first then tier-second, never concatenated, never
ranked, never scored. The coach is instructed to say each distinct tier sentence
once for the set of figures it covers.

**Left to /ship's design phase.** The description's wording for the grouping
rule; how the report page groups on its own surface.

**Acceptance criteria.**

- Every rendered figure maps to exactly one tier sentence — asserted by walking
  the figure set, not by restating a list.
- The three sentences are **byte-identical** to `AssuranceTierCopy`; no
  rewording (RFC 179, and the v19 pin).
- **RFC 179 D10 holds:** no number, score, rating or ordering of the tiers
  appears anywhere.
- The status sentence and the tier sentence remain two sentences, status first.
- A mixed-tier college is tested: the grouping must produce two headers, not one
  wrong one.
- Report word count: **re-derive from a fresh measurement (SD1-SD4)**; the
  `≤ 1,170` here came off the superseded 1,793 chain. Ratchet the existing
  `WORD_CEILING` down; do not add a second counter.
- No DDL. No prompt version expected; if one proves necessary, name the
  rollback.

**Reachability / the door.** Both existing doors.

**First-session test.** A family still learns that the median-debt figure is a
different kind of number from the tuition figure — once, clearly, instead of
seventeen times.

---

## `concise/06/the-coach-answer-shape`

**Needs:**

- BLOCKS concise/03/one-rule-one-home — technical. Both mint a coach prompt
  version from the same file; landing them out of order means the second
  re-writes the first's seed. `03` deletes, `06` adds.
- PREFER concise/02/the-payload-says-it-once — product judgement. Judging the
  coach's length against a payload we already know is bloated measures the wrong
  thing.

**What to build and why.** With the payload thin and each rule in one home, the
remaining verbosity is delivery. Gate-1 **D7** allows a table. External
evidence: concise pages measure **58% better** usability and scannable **47%
better** (NN/g); readers read at most **28%** of words; and only **51.5%** of a
generative assistant's sentences are citation-supported (arXiv:2304.09848) — so
fewer sentences is a **correctness** intervention, not only a style one.

**Foundations.** `prompts/coach-system-prompt.md`, the prompt-version
convention, `SystemPromptCatalogTest`.

**Decided here.** Four rules enter the prompt:

- **Front-load the number.** The first sentence of a money answer contains a
  figure and whose it is.
- **Parallel figures go in a table**, one row each, when there are three or more
  of the same kind.
- **Units live in the header, once** — "$" and "/yr" belong in the column head,
  not in every cell.
- **Hedge where the hedge changes the decision**, once per answer, not per
  figure.

**Left to /ship's design phase.** The exact prompt wording; whether the table is
markdown or a structured block the client renders; how the rules interact with
the existing "same four lines in the same order" instruction (`:48`).

**Acceptance criteria.**

- **Deletion or addition, never rewording** of a pinned line or a paragraph
  opener.
- Every gate-1 D1 item is still sayable: an evaluation set of real money
  questions is answered and checked for the figures, statuses, tiers, years,
  publishers and the at-home assumption. **A named evaluation, not a vibe
  check** — the RFC records the question set and the before/after.
- The at-home `$0` assumption is still stated as **ours** (RFC 166 §7), and
  loans are still never subtracted from a price (RFC 175).
- The new prompt version and its rollback version are named in `service.conf`
  and in the RFC. Rollback is an env-var flip.
- No DDL.

**Reachability / the door.** The existing coach conversation.

**First-session test.** A family asks what three schools cost and gets the
number first, a table second, and one caveat — instead of four paragraphs before
the first dollar.

---

## `concise/07/quiet-provenance`

**Status:** DEFERRED

**Needs:**

- PREFER concise/01/the-page-says-it-once — product judgement. Collapsing text
  we are about to delete would hide the wrong thing. Overridable.
- PREFER concise/04/cite-once-per-block — same reason.

**CANCELLED 2026-09-11 by Ian ("okay. do it"), recorded as decision D16.** The
ID stays; a cancelled slice is never renumbered or deleted (non-negotiable 7).
It keeps `Status: DEFERRED` so `slice-board` parks it, and this paragraph is the
binding record that it will not be scheduled.

Two landed slices closed off most of what this slice was for:

- `concise/01` (RFC 188, D-b) moved the **income-band note INTO the assumptions
  block**. Gate-1 **D5** forbids hiding anything band-specific. So the
  assumptions block — this slice's main target — **can no longer be collapsed**
  without breaking a committed decision.
- `concise/08` (RFC 196) made `sourcesSection` carry a **live link to each
  college's own filing** — the first actionable thing on it. D5's rule, taken
  from CFPB, is that hidden content must not be essential to the task.
  Collapsing a section in the same week we made it useful is backwards.

What remains hideable is the standing "what this is not" prose minus the
never-collapse "this is not an offer" paragraph, plus `concise/05`'s tier-kinds
block. Small — and it would mean introducing the first `<details>` chrome in
`public-web` for a marginal first-screen gain, on a page already **21% shorter**
(1,767 -> 1,404 words).

_The finding worth keeping:_ this slice was not wrong when specced. **Two slices
composed to remove its value**, and neither could have known — `01` moved a note
up, `08` made a block useful, and between them the third slice's target
disappeared. A spec written all at once cannot see this; only re-checking each
slice against what actually landed can. (Chart's EXECUTE phase says exactly
that: "re-check the next slice's instruction against what actually landed — the
code wins over the spec.")

**What to build and why. ** Everything above removes words. This removes
**first-screen load** without removing a single word: standing,
non-school-specific material goes behind a labelled disclosure. Saving: ~400
words off the first screen at zero information cost.

**Foundations.** `CostReportPage.assumptions` (`:311`), `sourcesSection`
(`:1081`), `site.css`. **No `<details>` exists in `public-web/src/main` today**
— this is new chrome.

**Decided here.** The standing hedges and the sources section may collapse. Two
things **never** collapse (gate-1 D5, and CFPB's own limit that hidden content
must not be essential to the task): the `report-sources` "this is not an offer"
paragraph, and the `OverallAverage` note.

**Left to /ship's design phase.** Open-by-default or closed; the summary label;
print and share behaviour.

**Acceptance criteria.**

- **Zero words removed** — this slice's word count is unchanged by construction,
  and a test asserts the collapsed text is present in the DOM.
- Keyboard and screen-reader reachable; works with no JavaScript.
- **Printing and "save as PDF" expand everything.** A parent who prints the
  report must not lose a hedge — asserted via the print stylesheet.
- The two never-collapse items are asserted absent from any `<details>`.

**Reachability / the door.** The existing share link.

**First-session test.** A parent sees the schools and the numbers on the first
screen, with the method one click away rather than three scrolls away.

---

## GATE 2 — slices and decisions

Slice order: **`concise/01`, `02`, `03`** (wave 1, parallel) -> **`04`, `05`**
(wave 2) -> **`06`, `07`** (wave 3).

**D9 — The word target, corrected.** Gate-1 D4 set ≤ 900 words on a 3-school
report. Adding up every identified repeat gives **1,793 − 664 = 1,129**; 900
requires turning the merit and debt prose into a shared table, which
`report-verbosity` rates high risk under RFC 175 D9 (a shared table makes two
un-summable publishers look summable). _Default:_ **target ≤ 1,150 words**, with
per-slice budgets (`01` ≤ 1,480, `04` ≤ 1,230, `05` ≤ 1,170), each asserted in
that slice's test. **Do not merge the merit and debt tables** to chase 900. D4
stands as committed; D9 supersedes its number.

**D10 — The two defects ride with `concise/01`.** _Default:_ **yes.** Both are
in `CostReportPage.kt`, both are one-line conditionals, and the private-school
sentence is found by the same render the slice already measures. A separate
slice would need its own RFC for two lines.

**D11 — The hoist rule is derived, never hard-coded.** _Default:_ **derived** —
a row hoists when its values across arrangements are identical. Naming "tuition
and books" in code would let a future arrangement with its own books field
silently collapse two different numbers into one.

**D12 — How many new coach prompt versions does this brief mint?** _Default:_
**two** — one for `concise/03` (pure deletion) and one for `concise/06` (new
rules). Keeping them separate means the deletion can be rolled back
independently of the new answer shape; merging them would make one env-var flip
undo both.

**D13 — Where the measurement lives.** _Default:_ a **rendered word-count
assertion** in `CostReportPageTest` and a **payload byte-budget assertion** in
`CollegeCostChatToolTest`, both on fixed fixtures. A budget that only exists in
an RFC is a number nobody re-checks.

**D14 — `concise/04` writes the RFC that supersedes RFC 175 D10.** _Default:_
**yes**, in that slice's own RFC, per `rfc/INVARIANTS.md` — the RFC 175 file is
left as committed, and the new RFC states why a block-scoping header is still
attribution. The old per-sentence test is **replaced** by the new rule's test,
never merely deleted.

---

## Gate 2 outcome

**PASSED 2026-09-11.** Ian approved all defaults, verbatim: **"approve"**.
D9-D14 stand as written. D9 supersedes gate-1 D4's number (D4 itself is
immutable and left as committed).

## Dispatch log

**2026-09-11 — wave 1 dispatched as TWO slices, not three.**

Live-number check at dispatch (computed, not copied): highest RFC file **185**;
live `pipeline/rfc-178` (stale, 2d, 28 behind), `rfc-186`, `rfc-187`. Next free
RFC = **188**.

`ship-status` shows two runs live from **brief 0010** (`when-do-i-test`), both
in `designing`:

- `rfc-186` = `test/01/the-coach-knows-today`
- `rfc-187` = `test/02/does-this-school-want-a-score`

Both edit **`prompts/coach-system-prompt.md`** and both mint a coach prompt
version from it (RFC 185: `bin/prompt-seed` cuts a version at land iff the body
differs). Brief 0010's spec records them as CONFLICTS with each other for
exactly this reason.

**`concise/03/one-rule-one-home` is HELD.** It is not blocked by any `Needs:`
edge in this brief — it is held on a discovered cross-brief collision. The
hazard is worse than an ordinary rebase: `concise/03`'s whole method is
**deleting redundant sentences** from that file (DISCOVER 9: deletion only,
never rewording), while two live runs are **adding** sentences to it and each
cutting an immutable seed version. Rebasing a deletion pass across two
concurrent additions risks deleting a sentence that landed minutes earlier, and
no test would catch it — the added sentence would simply be gone.

`concise/01` (`public-web` render) and `concise/02` (chat-tool payload builders)
touch neither the prompt file nor the admissions tool, and are dispatched.

This also revises **D12**'s arithmetic in practice: with brief 0010 minting two
prompt versions, this brief's two (`03`, `06`) are the third and fourth in
flight. The decision is unchanged; the ordering is now explicit.

---

## Spec defects found during EXECUTE

Append-only. A defect here is a fact about this spec being wrong, recorded so a
later slice is not written against it.

**SD1 — `concise/01`'s "≤ 1,480 words" criterion was unreachable, and this
spec's own budget table proves it.** Found 2026-09-11 by the `concise/01` run,
which measured rather than argued. The budget table above allocates that slice
102 + 72 + 25 + 24 = **223 words**, while 1,793 -> 1,480 requires **313**. The
criterion and the table were written in the same document and disagree. The run
deleted **all three** per-school hints rather than the two budgeted, beat its
allocation, and still measured **1,530**. Ian accepted 1,530 (D-a, 2026-09-11).
The remaining ~90 words belong to `concise/04` and `concise/05`; they were never
this slice's to save.

_Cause, worth naming:_ the same error as gate-1 D4 one level down — a target
written before the arithmetic, then not re-checked against the arithmetic when
the arithmetic was added. Both times the fix came from someone measuring.
`concise/04`'s ≤ 1,230 and `concise/05`'s ≤ 1,170 are inherited from the same
unchecked chain and **must be re-derived from the real baseline** before either
run treats them as binding.

**SD2 — the 1,793-word baseline is stale.** The real 3-school render is **1,767
words** on `main@212fbe89`; `main` moved after the research report was written.
Every per-slice budget in this spec is quoted against 1,793. Re-measure at the
start of each remaining slice; do not copy a number from this file.

**SD3 — "the default key is mandatory, never optional" was too absolute.**
`concise/02` narrowed it: `figure_provenance` is absent when a college holds
**zero** figure provenance, because a mandatory default over an empty set would
be an invented value. Accepted 2026-09-11. The invariant that actually matters
survives and is the one to state in future slices: **exceptions can never exist
without a default.**

**SD4 — the research report's central payload assumption was wrong, and the byte
target inherited the error.** `research/coach-verbosity.md` reconstructed
`figure_statuses` on the assumption of **one** distinct `(tier, publisher)` pair
per college, giving 5,366 bytes. `concise/02` measured the real harness: **4,980
bytes** (`Six Statuses U`) and **3,365** (`Plain U`), and found **three**
distinct pairs per college — the Scorecard both **relays IPEDS cells** and
**originates two federal administrative records**, so a field could be
off-default on publisher while its tier was not what differed.

Consequence: the all-three-or-none exception shape this spec assumed reaches
only 55.3% / 59.5% smaller, missing gate-1 D4's ≥ 60%. The run amended the
object shape to **per-key resolution** (an entry carries `source` when the
publisher differs, and the tier code + its sentence **as one inseparable value**
when the tier differs; any omitted key is the default's). Approved 2026-09-11 —
it is inside "left to /ship's design phase", and it is a better design
independent of any byte target: it says what differs, rather than restating
everything.

_The pattern, now four for four._ SD1, SD2, SD3 and SD4 are all the same
failure: **a number or an absolute written before it was measured, then
inherited by everything downstream.** Gate-1 D4's ≥ 60% and ≤ 900, this spec's ≤
1,480 / ≤ 1,230 / ≤ 1,170, "mandatory, never optional", and the 5,366-byte
baseline were every one of them authored ahead of evidence. Each was caught by
an agent that measured rather than complied. **Standing instruction for the
remaining slices: re-derive every target from a fresh measurement at the start
of the run, and report the real number rather than weakening an assertion to
meet mine.**

**SD5 — `concise/04`'s and `concise/05`'s foundations and budgets drifted the
moment RFC 188 landed.** Reported by the `concise/01` run at close-out,
corrected in their slice text above the same day: `borrowingParagraphs` moved
`:1037` -> `:1274` and `sourcesSection` `:1081` -> `:1318`, and both slices'
word budgets came off the superseded 1,793 chain. Both now say **measure
first**, and both are directed to **ratchet the existing
`CostReportPageTest.WORD_CEILING`** rather than add a second counter. This is
the expected cost of writing seven slices against one snapshot of a moving tree;
the fix is the instruction to re-grep and re-measure, not a promise to keep the
numbers fresh in this file.

**SD6 — the brief's headline claim about prompt repetition was wrong, and the 4
KB legend bar was harmful.** Found 2026-09-11 by the `concise/03` run, which
measured before designing.

_The claim._ This spec and the research report both assert that the coach prompt
restates itself — "the money vocabulary is written out at least **four** times,
'loans are not a price reduction' **five** times" — and imply the repetition is
deletable. Measured: it is real, but **almost none of it can go under
deletion-only**, because each repetition carries unique per-paragraph content or
is pinned by a paragraph-scoped test assertion. Deleting the line would delete a
committed guard with it. **The real prompt saving is 491 bytes (1.7%),** and
four lines. The win in this slice is entirely on the TOOL DESCRIPTION side (~14
KB), not the prompt side.

_The stale numbers._ The DESCRIPTION is **18,386** bytes, not 16,383 — RFCs 189
and 190 both grew it. The prompt is **29,297 bytes / 183 lines**, not 158 — v28
added 7 lines and v29 added 18. Shared files drift under parallel runs faster
than a spec can track.

_The bar._ Gate-2 said the legend must reach **≤ 4 KB**. Measured, a readable
legend is 5,479 bytes and a version with the prose stripped out entirely is
4,072 — **24 bytes under the bar, 0.6% headroom**. So the bar (a) makes a test
that fails on the next unrelated key anyone adds, and (b) can only be met by
compressing English in the one artifact whose sole reader is a language model.
**The bar is withdrawn** (2026-09-11): build the readable legend, set the
ceiling at its real measured size with headroom, and report the real percentage.
~18,386 → ~5,500 is about **−70%**, and −70% readable beats −78% unreadable.

_Not a defect, stated so it is not read as one._ This slice makes the prompt NET
GROW (4 lines out, up to 16 in) while the context window shrinks ~14 KB. That is
one-rule-one-home working as designed: the rule count is unchanged, the
DUPLICATE count falls, and the model stops being told the same thing twice in
one window.

_The pattern, now six for six._ Every number this brief asserted before
measuring has been wrong: ≤ 900, ≤ 1,480, ≤ 1,230, ≤ 1,170, ≥ 60% (right answer,
wrong reasoning), 5,366 bytes, 1,793 words, 16,383 bytes, 158 lines, and ≤ 4 KB.
Six separate runs caught them by measuring. **The standing instruction stands
and should outlive this brief: measure at run start, set the bar at the real
number, and report a miss rather than weakening an assertion.**

---

## Post-gate addition: `concise/08/the-link-we-promised`, and a reorder

**Decision D15 (Ian, 2026-09-11, verbatim): "We should link to our sources."**
Appended, not an edit of a committed decision (chart non-negotiable 7).

**Needs:**

- CONFLICTS concise/07/quiet-provenance — both edit `sourcesSection` and the
  provenance area of `CostReportPage.kt`. A rebase risk, not an order; `07` is
  separately held pending `08` by product judgement, not by this edge.

**The defect.** `AssuranceTierCopy`'s `VOLUNTARY_SELF_REPORT` sentence ends _"We
link to the college's own file so you can see it."_ The Family Cost Report
renders **no link at all** — `href` count in `CostReportPage.kt` is **zero**. We
make a promise in our own words, on the surface a parent reads, and do not keep
it. The sentence is byte-frozen, so it cannot be reworded: the link must exist.

**What is already true** (measured 2026-09-11, do not re-derive):
`source_documents` (RFC 170, migration 0087) stores `source_url NOT NULL` +
`archive_url`, written by `CdsSeedLoader`; every CDS read already joins it;
`CdsCitation` already carries both and `putCitation` already emits them to
**chat** for admissions, merit, borrowing and aid policy. **The chat side is not
the gap.** The page is, in two shapes: merit holds the whole citation and prints
only the label (`CostReportPage.kt:1210`), and borrowing has its citation
**flattened to a prose header in the service** (`BorrowingWire.kt:230`) before
the page can see it — the RFC 177 defect shape again, a fact read from the store
and dropped a layer later. **No table, column, migration or prompt version.**

### Reorder: `concise/08` runs BEFORE `concise/07`

`concise/07/quiet-provenance` proposes collapsing `sourcesSection` behind a
disclosure. `concise/08` makes that same section carry a **live link to each
college's own filing** — the first genuinely actionable thing on it.

**Collapsing a section in the same week we make it useful is backwards.** A
provenance block a parent can click through to the source document is not the
same artifact as the prose block `concise/07` was specced against, and D5's rule
("hidden content must not be essential to the task") reads differently once the
content is a working link rather than a standing hedge.

So `concise/07` is **held pending `concise/08`**, and its scope is explicitly
**re-opened**: after `08` lands, decide again what may collapse, against the
page that then exists. This is not a `Needs:` edge — nothing technical blocks it
— it is a product-judgement hold, recorded here so the board's READY is not
mistaken for "should start".

**SD7 — `concise/07`'s foundations are stale.** It cites `assumptions` at `:311`
and `sourcesSection` at `:1081`. RFCs 188, 190 and 191 have all edited
`CostReportPage.kt` since. Re-grep, and re-measure the baseline: the page is
**1,401 words**, not the 1,793 this spec was written against.

**SD8 — the freeze protected a FILE when the thing at risk was a RULE, and the
rule lived in two files.** Found 2026-09-11 by the `concise/03` run, as a
near-miss caught in review.

_What happened._ `concise/03` is a deletion pass. It was held out of wave 1 for
a cross-session collision on `prompts/coach-system-prompt.md`, and a hold on
that file was negotiated with the `/chart tests` session. The collision arrived
through the **other** file the slice deletes from —
`CollegeCostChatTool.DESCRIPTION` — where **our own** `concise/05` (RFC 191) had
added three lines hours earlier: _"Say each distinct tier sentence ONCE for the
SET of figures on it … never once per figure."_ A rebase conflicted there,
`git rebase --continue` committed the conflict markers, and taking the legend
side would have **silently dropped RFC 191's rule**.

_Why no test would have caught it._ The only thing holding that rule was a
`description.contains(...)` assertion **inside the very test file `concise/03`
was rewriting**. A guard that lives inside the artifact under rewrite is
guaranteed to be edited by the person most likely to break it — in good faith,
because rewriting that file is the task. The fix re-points it at the **served
prompt body**, which is strictly stronger: the pin now lives outside the thing
being changed.

_Three rules out of one near-miss._

1. **A deletion pass must enumerate EVERY file it deletes from, and protect each
   one.** Not "the file we were worried about". The hold was scoped by where the
   danger was expected, not by where the change actually reached. This is the
   coordinator's error, not the run's.
2. **"Rebase first" is not sufficient. A deletion pass must re-derive its
   inventory after EVERY rebase**, because the base can move again mid-run.
   `concise/03` rebased first exactly as instructed and was still overtaken.
3. **A guard must not live inside the artifact it guards.** Nominated to
   `/principle`; the corpus does not appear to cover it.

_And a review practice worth naming._ Tier 0 found the committed conflict
markers by **checking the RFC's measured numbers against the actual tree instead
of reading the RFC**. An RFC is a claim; the tree is the fact. That is the same
discipline that corrected six of this brief's own numbers.

_Extending the contention rule._ SD-note: the rule this brief derived — _the
unit of contention is the FILE and the KIND of edit, not the brief and not the
wave_ — is correct but was applied to too few files. The full form is: **for a
deletion, the unit is every file the deletion touches, whoever is editing them,
including your own sibling slices.**

**SD9 — "set the ceiling with a little headroom" is self-contradictory, and I
said it.** Found 2026-09-11 by `concise/03`'s Tier 1 review.

I instructed the run to "set the ceiling at the real measured size **with a
little headroom**, document in the test that adding a key means consciously
raising it (the same contract `WORD_CEILING` carries)". The two halves
contradict each other. `WORD_CEILING` is set **exactly** at its measurement —
that is what makes "you must consciously raise this" a true statement. Headroom
makes it false: the run's 57 spare bytes are a free behavioural sentence
(_"Never estimate a missing figure."_ is 32 bytes), which is precisely what the
ceiling exists to stop creeping back.

**A ratchet is a measurement, not a budget.** Set it exact, every time.

**SD10 — "one rule, one home" was applied to ONE tool; three more have the same
defect.** Reported by `concise/03` and deliberately NOT absorbed into its run.
`FederalAidPolicyChatTool`, `CollegeListChatTool` and `MoneyProfileChatTool`
each carry behavioural rules duplicated between their tool DESCRIPTION and the
coach prompt, unguarded. That is three more slices of the same shape. **A /chart
decision, not a run's to widen** — recorded here, to be put to Ian rather than
decided inside a ship run.

_Also declined by that run, correctly, as a clean follow-up:_ four appended
sentences give the figure-status paragraph a second subject. Fixing it needs a
new `CoachParagraph` and reordered lines, which a **deletion-only** slice may
not do.

_One more principle candidate from the same review:_ the legend listed English
glosses where the payload carries enum tokens — a legend for values the model
never sees, in the artifact whose only job is to explain the payload. **A legend
must be derived from, or pinned to, the thing it describes.** Close to, but
distinct from, SD8's "a guard must not live inside the artifact it guards".

## Principle nominations from this brief

Filed here so they survive the runs' archives. To be taken to `/skill:principle`
with Ian, not edited into the corpus directly.

**P1 — The artifact under change cannot be its own witness.** _(Strongest
nomination; two independent instances, two briefs, one day.)_ The thing being
changed must not be the thing that attests to its correctness, because the
change and the check then move together and the check cannot fail.

- _Instance A (brief 0009, `concise/03`)_: RFC 191's rule was pinned ONLY by an
  assertion inside the very test file the slice was rewriting. Rewriting that
  file **is** the task, so the guard gets edited in good faith by the person
  most likely to break it. The rule was minutes from silent deletion.
- _Instance B (brief 0010, RFC 187)_: model-facing copy asserting its own
  correctness, caught in review.
- Generalisation credited to the `/chart tests` session, which spotted that two
  nominations were one principle.

**P2 — A ratchet constant is the measurement exactly, never measurement plus
headroom**, wherever its documented contract is "raise this consciously". Enough
slack for one sentence is enough for the regression it exists to prevent.
(`concise/03`: 57 spare bytes is a free behavioural sentence. My instruction
asked for headroom AND the conscious-raise contract; they are incompatible.
SD9.)

**P3 — Verify an RFC's measured numbers against the tree, not by reading the
RFC.** An RFC is a claim; the tree is the fact. This practice found committed
conflict markers in `concise/03` within minutes, and corrected ten of this
brief's own numbers.

**P4 — An error handler's cleanup must be scoped to the operation THIS code
started.** (RFC 193: `git rebase --abort` fired on any failure, including "an
operation already in progress", which would have destroyed an operator's own
paused rebase and reported it as ours.)

**P5 — A reader that classifies text must set `IFS=`.** (RFC 193, reproduced: a
directory named `␣product` classified as `product` and skipped the gate. RFC 194
pinned it with a negative control — deleting `IFS=` fails exactly one
assertion.)

**P6 — A fixture must strip what the production caller must keep.** A test that
builds its own git repo must clear the hook environment; the real caller must
not. Getting it backwards makes every assertion pass **against the wrong
repository** — green, and meaningless. (RFC 194.)

**P7 — A sweep must count the thing, not a spelling of it.** (RFC 196: the
source sweep counted `a(href`, allowlisting one of two valid anchor forms — the
second form was exactly the hole the test existed to close.)

**P8 — A legend must be derived from, or pinned to, the thing it describes.**
(RFC 192: the `assurance` entry hand-typed English glosses where the payload
carries enum tokens, explaining values the model never sees.) _Possibly a
special case of P1; let `/principle` decide._

---

## Post-gate additions: `concise/09` and `concise/10`

Two slices added 2026-09-11 on Ian's decision, after EXECUTE completed.
Appended, never edits of committed decisions (non-negotiable 7).

**D16 (Ian, 2026-09-11): `concise/07/quiet-provenance` is CANCELLED.** Recorded
above beside the slice. The ID is permanent and is never reused.

**D17 (Ian, 2026-09-11): "spec the one-rule-one-home work."**

**D18 (Ian, 2026-09-11): "spec link rot. I'm okay saying something and not
linking if the link is dead."**

---

## `concise/09/three-more-homes`

**Needs:**

- CONFLICTS concise/10/no-link-to-a-grave — both may mint a coach prompt version
  from `prompts/coach-system-prompt.md`. An ADD/ADD overlap: a rebase risk, not
  an order. Neither deletes, so neither needs a window.

**What to build and why.** `concise/03` (RFC 192) gave the cost tool one home
per rule: its description became a pure schema legend, 46 behaviour rules
already stated in the prompt were deleted, and 17 moved to the prompt. Net
context window **9,703 bytes smaller**. Three tools still carry the defect,
unguarded — RFC 192 D10: `FederalAidPolicyChatTool`, `CollegeListChatTool`,
`MoneyProfileChatTool`. The model is told the same rule twice in one context
window, in two wordings, and when someone updates one copy the other drifts
silently.

**ONE slice, not three — and the measurement says so.** The three `DESCRIPTION`
constants are **1,682 + 1,881 + 1,115 = 4,678 bytes** total (measured
2026-09-11, `FederalAidPolicyChatTool.kt:256`, `CollegeListChatTool.kt:545`,
`MoneyProfileChatTool.kt:295`). That is **a quarter** of the cost tool's 18,580.
Three RFCs, three gates and three prompt versions for 4.7 KB would cost more
than the defect. One slice, **one prompt version**, three files.

**Set expectations honestly:** the win here is roughly **a tenth** of
`concise/03`'s, and the real reason to do it is drift prevention and
consistency, not bytes. Say that in the RFC rather than implying another 14 KB.

**Foundations.** RFC 192's landed pattern is the whole specification:
description = schema legend (what each key means, what values it takes);
behaviour = prompt. `LEGEND_BYTE_CEILING` is the precedent for the assertion
shape.

**Decided here.** Each tool's `DESCRIPTION` becomes a schema legend. A behaviour
rule the prompt already states is DELETED. A behaviour rule with no home MOVES
to the prompt. Per-parameter `description` strings inside the JSON schema are
legitimate legend content and stay.

**Left to /ship's design.** Whether the three tools share one legend convention
or keep their own; the new prompt version number (compute it live).

**Acceptance criteria.**

- **A named before/after rule map**, as RFC 192 produced: every rule that exists
  today still exists somewhere, each mapped to its surviving home. That is
  gate-1 D1 applied to instructions, and it is the deliverable that proves
  nothing was lost.
- **MEASURE AT RUN START. Do not take a byte target from this document.** Ten
  spec defects in this brief were numbers written before measuring (SD1-SD10).
  Set every ceiling at the real measured size, **exactly**, with no headroom
  (SD9, P2).
- Guards pinned **outside** the artifact being rewritten (P1): assert against
  the SERVED prompt body, not against a constant in the file under edit.
- Deletion-only in the prompt; no paragraph opener reworded; the nine
  byte-frozen sentences untouched.
- Name the rollback version, and say whether it is a WHOLE or a HALF rollback —
  RFC 192's is a half, because reverting the prompt without reverting the code
  leaves the moved rules in neither home.
- No DDL, no table, no column.

**Reachability / the door.** Invisible to a family by design: the same coach,
told each rule once. Its effect is fewer competing instructions in one context
window.

**First-session test.** Identical answers; less duplicated instruction.

---

## `concise/10/no-link-to-a-grave`

**Needs:**

- CONFLICTS concise/09/three-more-homes — see above. Rebase risk only.

**What to build and why.** `concise/08` (RFC 196) made the Family Cost Report
link to each college's own filing — 5,048 urls, pointing at colleges' own PDFs.
Some are already dead and more will die. A parent who clicks a citation and
meets a 404 learns that we do not check what we point at. Ian: _"Let's try and
not link to 404s."_

**Foundations.** `source_documents` (RFC 170) already stores
`source_url NOT NULL` **and a nullable `archive_url`** — our mirror, stored and
**never rendered** today. `CdsSeedLoader` writes the rows. RFC 196's
`CdsSeedLoaderTest` already walks all 5,048 urls to assert their scheme, so a
corpus-wide sweep at build time is a pattern that exists and passes.

**Decided here.**

- **Liveness is checked at INGEST, never at render.** A family must never wait
  on a college's web server, and a report must never be slower because a
  school's site is down.
- **The result is RECORDED**, so a dead link is a known fact rather than a
  surprise at render time.
- **D18 (Ian):** where the college's own file is dead, **say the citation and do
  not link it.** The attribution text still appears — a family still learns
  whose claim it is, in which cycle, from which document — it simply is not
  clickable. Nothing becomes unreachable (D1); only the anchor goes.

**Left to /ship's design phase.** Where the liveness result is stored (a column,
a separate table, or a build-time artifact) — **if it needs DDL, present the
proposed DDL explicitly at the /ship approval gate** (non-negotiable 6, brief
0001 D10); how often the check re-runs; whether `archive_url` is ever offered,
and under what words.

**Acceptance criteria.**

- No render-time network call. Assert it.
- A dead source renders the citation as **text**, not as a link, and the page
  still states who claimed the figure and in which cycle.
- **The frozen sentence stays true.** `AssuranceTierCopy`'s
  `VOLUNTARY_SELF_REPORT` copy says _"We link to the college's own file so you
  can see it."_ It is byte-frozen and cannot be reworded. So **that sentence
  must not be said about a figure whose link we have dropped.** Walk the surface
  and assert it, as RFC 196 did — it is vacuous on the page today and must stay
  true when a CDS figure reaches it.
- A checker that cannot reach the network must **not** mark everything dead: an
  unreachable CHECK is our gap, not the school's silence (RFC 166 §6). Fail
  closed — no result means keep the link and record that the check did not run,
  the shape RFC 182 used for its skipped cross-source check.
- Report real counts: how many of the 5,048 are dead. **Measure; do not
  estimate.**

**Reachability / the door.** The existing share link, and any coach answer
citing a Common Data Set figure.

**First-session test.** A parent clicks a citation and reaches the college's
filing — or, where it is gone, reads the same attribution with nothing to click,
and is not sent to a dead page.
