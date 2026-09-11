# Coach verbosity: why the money answer is long and repetitive

Research for product brief 0009 "say it once". READ-ONLY; no code was changed.

## 1. Method and evidence

What I did, in order:

1. Located the live prompt version. It is **v27**, not v26 —
   `service/src/main/resources/service.conf:186` reads
   `systemPromptVersion = "v27"` (line 187 allows the
   `COACHING_SYSTEM_PROMPT_VERSION` override). RFC 185 minted v27; v25 and v26
   were minted by runs that never touched the prompt (`service.conf:158-163`).
2. Read `prompts/coach-system-prompt.md`, one sentence per line. **Measured**
   `wc -l -w -c` = `158 4523 25201` — 158 lines, 4,523 words, 25,201 bytes
   (~6,300 tokens at 4 bytes/token).
3. Read `CollegeCostChatTool.kt` (1,913 lines) and the seams it renders from:
   `CollegeCostService.kt` (`figureStatusesOf`, `statusNoteOf`),
   `canonical/FigureStatusCopy.kt`, `canonical/AssuranceTierCopy.kt`,
   `canonical/MoneySourceCopy.kt`, `CostField.kt`, `CostBreakdown.kt`,
   `ComparisonBasis.kt`.
4. **Measured** the tool DESCRIPTION: I extracted every string literal between
   `CollegeCostChatTool.kt:1709` (`val DESCRIPTION =`) and line 1911
   (`"Read-only: this tool changes nothing."`) and concatenated them. Result:
   **17,782 literal bytes**; with `${...}` interpolations replaced by nominal
   20-char values, **16,383 bytes / 2,660 words** (~4,100 tokens). This is sent
   in the tool definition on **every** turn.
5. **Reconstructed, not captured**: I could not find a golden serialized payload
   in the test tree — `grep -n "figure_statuses\|assurance_statement"` over
   `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`
   (3,994 lines) returns only line 2214, a helper comment, no expected JSON
   blob. So I did **not** capture a real payload from a running service. What I
   did instead: took the **real copy strings from the source files** and the
   **real emitter structure** and assembled the array in Python, then counted
   bytes. The strings are real; the assembly is reconstructed. Say so when
   quoting these numbers.
6. Two sub-agents ran in parallel and wrote to
   `product/0009-say-it-once/research/_parts/`: `rfc-history.md` (RFC
   175/177/179/181/183/185 accumulation and rollback convention) and
   `other-tools.md` (search / similar / admissions / federal-aid payload sizes).
   Their findings are folded in below where cited.

---

## 2. Ranked drivers of verbosity

### D1 — `figure_statuses[]`: one entry per field per college, each carrying a full assurance paragraph

This is the largest single source of repeated bytes.

`figureStatusesOf` walks **every** `CostField` and emits an entry for every
field the read holds provenance for — **shown as well as blank**:

> `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt:1990-1993`
> "A SHOWN figure gets an entry too, and used to be dropped here. That drop is
> why RFC 177's `reported` sentence was unreachable on both surfaces, and it is
> why an assurance tier would have been invisible on every dollar amount a
> family reads"

`CostField` has **17 members** (`CostField.kt:57-166`): sticker COA, three
tuition tiers, three fees-only tiers, `net_price`, median debt, median earnings,
three housing-and-food lines, books, three other-expenses lines.

Each entry is rendered by `figureStatusObject`
(`CollegeCostChatTool.kt:752-777`) with keys `field`, `status`, optional
`statement`, `assurance`, `assurance_statement`, `source`, optional
`held_academic_year`. `assurance_statement` is **always written**
(`CollegeCostChatTool.kt:761-762`).

There are only **three** possible `assurance_statement` values, ever
(`canonical/AssuranceTierCopy.kt:60-76`). Measured lengths:

| tier                    | bytes |
| ----------------------- | ----- |
| `administrative_record` | 171   |
| `mandatory_survey`      | 126   |
| `voluntary_self_report` | 111   |

e.g. `mandatory_survey`:

> "The college had to report this to the federal government, and the form checks
> it against last year's answer. Nobody audits it."

And for a non-shown `reported` figure the `statement` is
`FigureStatusCopy.statementOf` (`canonical/FigureStatusCopy.kt:115-117`):

> `"This figure comes from ${MoneySourceCopy.labelOf(source)}."`

where `labelOf` is e.g.
`"the U.S. Department of Education's IPEDS survey of
college costs"`
(`canonical/MoneySourceCopy.kt:36`).

**Reconstructed measurement** (real strings, assembled by me — not captured from
a running service):

- One college, 17 fields all `reported`/`mandatory_survey`/`ipeds_ic_ay`,
  compact JSON: **4,645 bytes**, of which **3,468 (75%)** is the byte-identical
  `assurance` + `assurance_statement` + `source` triple repeated 17 times.
- Realistic mix (10 shown, 7 blank): **5,366 bytes** per college, **~16,100
  bytes** for three schools — ~4,000 tokens of `figure_statuses` alone, carrying
  exactly **one** distinct assurance sentence and **two** distinct status
  sentences.
- Stated once per college instead: **1,366 bytes**, a **~4x** cut on this key.

The tool description then instructs the coach to _speak_ every one of these:

> `CollegeCostChatTool.kt:1736-1741`: "Every entry also carries `assurance` …
> `assurance_statement` is the sentence to say that in - say it beside the
> figure, after the `statement` sentence where there is one, and never re-word
> it, rank the three or turn them into a score."

"say it beside the figure" is a **per-figure** instruction. That is the single
sentence most directly responsible for a wall of hedges in the rendered answer.

### D2 — the tool DESCRIPTION is ~16 KB of prose, and it is 100% duplicated instruction

**Measured: 16,383 bytes / 2,660 words** (`CollegeCostChatTool.kt:1709-1911`).
It is not a schema; it is a second system prompt. Substantial parts of it
restate the system prompt almost verbatim. Compare:

- Description (`:1719-1721`): "`figure_statuses` says WHY a field has no number,
  one entry per field, each carrying a status and the `statement` to say it in -
  say that sentence rather than writing your own."
- Prompt (`prompts/coach-system-prompt.md:124`, `:129`): "When a figure has no
  amount, the result says why in a `figure_statuses` entry, and every entry
  carries the sentence to say" / "Say the entry's own sentence rather than the
  status word beside it."

Same rule, twice, every turn. Other duplicated pairs: the at-home `$0`
assumption (description `:1836-1842` vs prompt `:52-56`); merit-aid share
framing (`:1770-1776` vs `:42-43`); the comparison-basis five lines
(`:1893-1905` vs `:70-77`); the residency-tier vocabulary (`:1785-1806` vs
`:24-28`).

### D3 — the system prompt is 158 free-standing imperative sentences, almost all "always say X"

**Measured: 25,201 bytes, 4,523 words, 158 lines.** The rhetorical shape matters
more than the size: line after line is a _speech act obligation_. A partial
count of length-driving verbs in the file:

`Say / say ...` is the dominant verb — lines 22, 27, 48, 51, 53, 57, 65, 66, 67,
71, 76, 86, 87, 90, 94, 96, 102, 104, 109, 111, 114, 117, 129, 135, 137, 139,
140, 143, 144, 148, 150, 153, 154, 157, 158. The load-bearing per-figure ones:

- `:32` — "**Always attribute each cost figure to the source the tool names
  beside it**, and when a school doesn't report a figure, say that plainly
  rather than estimating." ← _the "once per figure" rule, in the prompt._
  (RFC 177)
- `:48` — "Say the same four lines in the same order every time: tuition and
  fees, housing and food, books and everyday costs, and the total for that way
  of living."
- `:49` — "Always name which arrangement you are quoting … because the same
  school has a different price for each."
- `:53-55` — "Say the assumption in the same breath, in these words. / Living at
  home, we count no food-and-housing cost: eating at home is not free, but it is
  not a new cost that enrolling creates, so the at-home total counts it as zero.
  / That is our assumption, not a figure any school published." ← a fixed
  **48-word** block the coach is told to recite verbatim.
- `:62` — "The tool gives each academic year together with the figures it
  covers: **say a number with the year that lists it**" ← per-figure year stamp.
- `:70-71` — "the cost tool gives you a `comparison_basis`: **say those five
  lines first**, as ordinary sentences above the table … Say who the figures
  describe, which residency every tuition and fees figure assumes, which way of
  living every school is priced for, which academic year each figure comes from,
  and what a net price already counts as aid." ← **five mandatory sentences
  before the first number in any two-school answer.**
- `:114` — "**Always say which award year a figure is for**, and attribute it to
  the source the tool cites." (federal aid, RFC 159)
- `:135` — "give the year and the source with the figure." (aid policy, RFC 170)
- `:143-144` — "say that cohort out loud with its year. / The school reported
  these figures about itself, so name the school as the one saying it."
  (RFC 175)
- `:157` — "Every ranked result names the price it was ranked on; read that name
  before you say a number" (RFC 169)

Note the arithmetic: `:71` (5 basis sentences) + `:32` (source per figure) +
`:62` (year per figure) + D1 (`assurance_statement` per figure) means a
three-school, four-line comparison table is preceded by 5 sentences and followed
by up to 3x4 = 12 attribution/assurance sentences, most of them identical.

Counter-pressure exists but is weak and generic:

- `:3` "Be concise and concrete."
- `:8` "Never write a wall of text: hold paragraphs to two or three sentences…"
- `:9` "Equally, do not dress up a one-line answer: a short factual reply is a
  sentence, not a list of one."

Three brevity sentences against ~35 "say X" sentences. The prompt is
overwhelmingly biased toward addition.

Two provenance facts from `_parts/rfc-history.md` matter here:

- **No RFC between 175 and 185 complains about prompt length or answer
  verbosity.** The only length rules in the corpus are v1's `:3` "Be concise and
  concrete." and RFC 124's `:8-9` wall-of-text rule. Nothing has ever pushed
  back on growth.
- Of the five RFCs named in the brief, only **two** changed a word of the
  prompt: RFC 175 appended `:142-148` (v21) and RFC 177 edited exactly **two**
  interior sentences, `:32` and `:107` (v23). RFC 179 minted no version, RFC 181
  changed "NOT ONE WORD", RFC 183 changed nothing, RFC 185 changed whitespace
  only. The prompt grew by **append-only paragraphs**, which is precisely the
  mechanism that produced the duplicate rules listed in §3.

### D4 — the same tuition figure is emitted three times per college

`ArrangementCost.lines` = `listOfNotNull(tuitionLine) + componentLines`
(`CostBreakdown.kt:319`), and `breakdownObject` writes each arrangement's lines
under `on_campus`, `off_campus`, `with_family`
(`CollegeCostChatTool.kt:1031-1042`). The residency-selected tuition figure is
therefore the **same amount under the same wire key in all three arrangements**.
It is also emitted a fourth time as a top-level `tuition_and_fees_*` key. The
coach that reads it three times tends to say it three times, once per "way of
living" block that prompt `:48` tells it to lay out identically.

### D5 — `comparison_basis` carries a code AND a full sentence for six facts, plus per-college arrays

`comparisonBasisObject` (`CollegeCostChatTool.kt:102-130`) emits `population`,
`residency`, `blended_figure_basis`, `living_arrangement`, `academic_years`,
`aid` — each with `basis` (a code) **and** `statement` (English). Two of them
(`residency`, `blended_figure_basis`) additionally carry a `by_college[]` array
where **every entry repeats a per-school `statement`**
(`residencyAnsweredBasisObject:157-176`, `collegeResidencyObject:186-201`,
`collegeBlendedFigureObject:225-233`). Real statements are long, e.g.
`ComparisonBasis.kt:542-545`:

> "Every school here is public, and every tuition and fees figure here is the
> one a family living in …"

So for N schools the residency fact is stated **1 + N** times, and the blended
fact **1 + N** times, before the coach has said a dollar.

### D6 — codes and sentences ship as pairs everywhere ("say it as a code, and say it in English")

This is a deliberate, repeated convention, documented as such:

> `CollegeCostChatTool.kt:757-763`: "the `income_band` + `income_band_label`
> convention (RFC 151 D-D) applied to the six statuses … the same convention a
> third time."

Every `basis`/`statement`, `arrangement`/`arrangement_label`,
`assurance`/`assurance_statement`, `status`/`statement`,
`income_band`/`income_band_label` pair doubles the byte cost of a fact. The
pairs are individually justified (a reader should not parse English to learn a
code); the aggregate cost was never budgeted.

### D7 — other tools add their own boilerplate on top

See `_parts/other-tools.md` for the measured/reconstructed sizes of
`search_colleges`, `similar_colleges`, `find_college`,
`college_admissions_profile` and `federal_aid_policy`. The structural point is
that `coaching.maxToolRounds = 8`
(`service/src/main/resources/service.conf:194`) — up to **eight rounds of tool
payloads accumulate in one turn's context**, each carrying its own per-row
repeated notes (`price_ruler`, `residency_tiers`, `axes_scored`,
`data_availability`).

---

## 3. Honesty guarantees vs. accidental repetition

**Must survive any compression** (each is a named RFC guarantee; retiring one is
a product decision, not a wording change):

| Guarantee                                                                                        | Where                                                                                    | RFC                 |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- | ------------------- |
| A figure is attributed to the publisher **that actually published it**, never to an assumed one. | prompt `:32`; `FigureStatusCopy.statementOf`; `MoneySourceCopy.labelOf`                  | RFC 177             |
| The three assurance tiers are said in the domain's own words, never re-worded, ranked or scored. | **not in the prompt at all** — `AssuranceTierCopy:60-76` + tool description `:1736-1741` | RFC 179             |
| `not_collected_by_us` is **OUR** gap, never "the school did not report it".                      | prompt `:127-128`; `FigureStatusCopy:79`                                                 | RFC 166 §6          |
| The at-home `$0` housing line is **ours**, attributed to unicoach, stated in words.              | prompt `:52-56`; `AT_HOME_ASSUMPTION_STATEMENT`; `assumed_by_unicoach` key               | RFC 166 §7          |
| A number is quoted with **its own** academic year; two years are never mixed in a total.         | prompt `:62`; `datedFiguresObject`; `held_academic_year`                                 | RFC 166 §3, RFC 183 |
| The in-state basis of `net_price` / sticker COA is never offered to an out-of-state family.      | prompt `:107-112`; `blended_figure_basis`                                                | RFC 157             |
| No total is summed from partial parts.                                                           | prompt `:57`, `:74`; `ArrangementCost.totalPerYearUsd:340`                               | RFC 149 D-C         |
| Loans and debt are never subtracted from a price.                                                | prompt `:19`, `:61`, `:69`, `:116`, `:146-147`                                           | RFC 175             |
| Borrowing figures name the graduating cohort, its year, and the school as reporter.              | prompt `:143-144`                                                                        | RFC 175             |
| A comparison names the basis it holds constant.                                                  | prompt `:70-71`                                                                          | RFC 151             |

**Accidental / mechanical repetition — compressible with no honesty loss:**

1. `assurance_statement` repeated 17x per college when there are only 3 distinct
   values (D1). The _guarantee_ is that the tier is said; nothing in RFC 179
   requires it be said once per number.
2. `"This figure comes from <publisher>."` repeated per field when almost all
   fields at a college share one publisher (D1).
3. Tuition emitted 3-4x per college (D4).
4. `residency` / `blended_figure_basis` statements repeated 1 + N times (D5).
5. The tool DESCRIPTION restating rules the system prompt already states (D2).
   This is pure duplication: two copies of one rule in one context window.
6. The 158-sentence prompt's own internal restatements: the money vocabulary
   ("tuition and fees, housing and food, the published price, a financial aid
   offer") is written out **at least four times** — `:16-17`, `:68`, `:97`,
   `:106`.
7. Loans-are-not-a-price-reduction is stated **five** times — `:19`, `:61`,
   `:69`, `:116`, `:147`.

---

## 4. Options

### 4a. What the TOOL could return

**O1 — hoist the assurance tier and the source to the college, keep the
exceptions per figure.** Emit once per college:
`{"assurance":"mandatory_survey","assurance_statement":"…","source":"ipeds_ic_ay"}`
as e.g. `figure_provenance_default`, then emit a `figure_statuses` entry
**only** for fields that differ from the default or that carry a status other
than `reported`. Reconstructed saving on the mixed one-college case: **5,366 →
~1,400 bytes (~75%)**. No fact is dropped: every figure still resolves to
exactly one tier and one publisher, and the resolution is mechanical rather than
English. Risk: a reader must apply the default. Mitigate by making the default
key mandatory and by keeping a per-field `source` code (not sentence) where it
differs.

**O2 — stop emitting `assurance_statement` on every entry; emit the tier code
plus a single `assurance_glossary` object with the 3 sentences.** Strictly
weaker than O1 but a much smaller change; saves the 126-171 byte sentence x17xN.

**O3 — drop the repeated tuition line from each arrangement** and emit it once,
with the arrangement objects carrying only their own components and the total.
Saves little in bytes but removes the cue that makes the coach recite tuition
once per living situation (D4).

**O4 — emit `by_college` statements only where a school DIFFERS from the
call-level basis.** Today `by_college` is written unconditionally
(`CollegeCostChatTool.kt:174`). Where every school is on one basis, the
call-level `statement` already says it; the per-school sentences add nothing.
Precedent already exists in the same file: `CHOSEN_BY_COLLEGE_KEY` is emitted
only when the schools are not all on one plan (`:266-270`, quoted comment: "with
one plan everywhere, the statement and the per-college keys already say it, and
a second per-school list would invite the coach to narrate a difference there is
none of"). Apply that exact rule to `residency.by_college` and
`blended_figure_basis.by_college`.

**O5 — cut the DESCRIPTION to a schema legend and move every behavioural rule to
the prompt (or vice versa), so each rule exists once.** Target: ~16 KB → ~3 KB.
This is the biggest pure-duplication win and it changes no guarantee, only where
it is written. It does need care: the description is the only place some keys
are explained at all.

### 4b. What the PROMPT could say

**O6 — "state the source once per answer, not once per figure."** Concretely,
replace prompt `:32`:

> current: "Always attribute each cost figure to the source the tool names
> beside it, and when a school doesn't report a figure, say that plainly rather
> than estimating."

with a rule of the shape: _when every figure in an answer shares one publisher,
name that publisher once for the whole set; name a publisher again only for a
figure whose publisher differs from the set's._

**Risk assessment against RFC 177 / RFC 179.** RFC 177's guarantee is
_correctness_ of attribution, not _cardinality_. Its own framing is the "one
hedge seam": the defect it fixed was the prompt naming **the Scorecard for every
cost figure** when the loader ranks two IPEDS surveys above it —
`service.conf:145-150`: "Both now say the SOURCE THE TOOL NAMES: the payload
derives the publisher from the row that actually won … so the old prose was
wrong for most figures." A once-per-answer rule keeps that fix intact
**provided** the "once" is derived from the payload's own set of sources rather
than from a default the model picks. The payload already ships that set:
`SOURCE_KEY` at the top level, "Every result names the publishers its own
figures came from" (`CollegeCostChatTool.kt:1712`), and it is emitted
**absent-never-empty** (`:90-94`). So the honest form of O6 is: _say the
top-level `source` list once; say a per-figure source only where a figure's
`source` differs from it._ That is a **safe** compression, and the payload
already carries exactly the data needed to enforce it.

The residual risk is real but narrow: a mixed-publisher answer where the coach
says "these come from IPEDS" and one figure is Scorecard. Mitigations: (a) O1
makes the exception explicit in the payload; (b) test the mixed case explicitly
(see §5).

**RFC 177 already set the precedent for a roll-up.** RFC 177 D5
(`rfc/177-one-hedge-seam.md:255-258`) ships a once-per-answer sentence of the
form "The cost, price and federal debt figures come from …". So a
once-per-answer rule is not a new idea in this corpus; it is an existing shape
that the prompt's `:32` "each cost figure" wording talks over. Changing `:32` is
a genuine wording change and needs a new prompt version, but it does not
contradict RFC 177's decision.

**RFC 179 is the tighter constraint — and it never reached the prompt.** My
sub-agent's mapping of every seed body to every prompt line shows RFC 179
produced **no prompt version at all** (`rfc/179-assurance-tiers.md:54` "No DDL.
No new prompt version."; D8 at `:234`). The per-figure assurance instruction
lives **only** in the tool DESCRIPTION (`CollegeCostChatTool.kt:1736-1741`),
which is code, not a versioned immutable prompt row. That is good news for
compression: changing it is an ordinary code change with an ordinary test, not a
prompt-version mint. Its per-figure framing is nevertheless deliberate:
`AssuranceTierCopy.kt:29-32` — "A tier sentence and a status sentence are always
composed as TWO sentences, in that order … and never concatenated into one." And
the description (`:1739-1741`) says "say it **beside the figure**". Compressing
this is a _product_ decision about how soft a number may look, and it is the
same question brief 0008 asked. The defensible middle: **group by tier**, not by
figure — "all of these figures are from the college's required federal filing
<tier sentence>; the median debt figure is different <tier sentence>". This
keeps every number covered by exactly one tier sentence, keeps the sentences
verbatim and unranked, and drops the repetition. It should be checked with Ian,
because RFC 179 D7's stated intent was that the tier be visible _on every dollar
amount a family reads_ (`CollegeCostService.kt:1991-1993`).

**O7 — add an explicit anti-repetition rule to the prompt.** Something in the
spirit of `:9` but aimed at facts, e.g. _"Say each qualification once per
answer. If two figures share a source, a year, a residency basis or an assurance
tier, say it once for the group and never repeat it per number."_ This is one
sentence and it directly targets D1, D3, D5.

**O8 — deduplicate the prompt itself.** The money vocabulary (`:16-17`, `:68`,
`:97`, `:106`) and the loans rule (`:19`, `:61`, `:69`, `:116`, `:147`) can each
collapse to one statement plus references. Measured headroom: the file is 4,523
words; a conservative pass on the seven duplications listed in §3 removes
several hundred words with no rule lost. **Caution:** several of those
repetitions are byte-frozen into immutable prompt rows and are asserted by
`SystemPromptCatalogTest`; see §5.

**O9 — give the coach a shape for a money answer**, since today it has 158 rules
and no template. e.g. _"A cost answer is: the number(s), then at most three
qualifying sentences for the whole answer (basis, year, source+assurance), then
the one question."_ A template caps length structurally in a way that a list of
prohibitions cannot.

### Suggested ordering (cheapest, safest first)

1. O5 (de-duplicate DESCRIPTION vs prompt) — pure duplication, no guarantee
   touched.
2. O4 (`by_college` only where it differs) — precedent already in the same file.
3. O1/O2 (hoist assurance + source per college) — biggest byte win.
4. O6 restricted form (source once per answer, exceptions named) — safe under
   RFC 177.
5. O7 + O9 (anti-repetition rule + answer template) — behavioural, needs eval.
6. O3, O8 — cleanups.
7. RFC 179 tier grouping — **ask Ian**; it edges a stated guarantee.

---

## 5. How to test and roll back

### The rollback convention

It is stated in `service/src/main/resources/service.conf` at every version, e.g.
lines 151-152:

> "Rollback is `COACHING_SYSTEM_PROMPT_VERSION=v22` — the v22 row is immutable
> and stays in the catalog."

and lines 179-181 for the current one:

> "Rollback is `COACHING_SYSTEM_PROMPT_VERSION=v26` — that row is immutable and
> stays in the catalog."

So: a prompt change is a **new seed file**
(`db/schema/seed-coach-system-prompt-vNN.sql`, generated by `bin/prompt-seed`
from `prompts/coach-system-prompt.md` — RFC 181/185), a bump of
`systemPromptVersion` in `service.conf`, and a narrative comment naming the
rollback version. Rollback is an **environment variable flip**, no deploy of
code and no migration. Old rows are never edited.

`service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` (2,173
lines) enforces this. It asserts, among other things, that the served body
equals `prompts/coach-system-prompt.md` verbatim (`:96`, `:126`, `:367-378`) and
that each prior row remains selectable, failing with e.g. (`:1668-1672`):

> "the v26 row must remain selectable, or `COACHING_SYSTEM_PROMPT_VERSION=v26`
> is not a rollback"

**Consequence for O8:** prior rows recite specific sentences and the test pins
them. `FigureStatusCopy.kt:88-92` records this explicitly:

> "`agentlessStatementOf` is byte-frozen (D2): `SystemPromptCatalogTest` asserts
> that the IMMUTABLE v19 prompt row recites the shipping sentence for every
> status, so rewording the six would fail against a row no new seed can fix."

So the six status sentences **cannot be reworded** without breaking the v19
assertion. Compression must remove _repetitions of_ those sentences, not the
sentences.

The pinning is broader than that. Per `_parts/rfc-history.md`, **~89 of the 158
prompt lines have their text pinned as string literals inside
`SystemPromptCatalogTest.kt`**, and the `CoachParagraph` opener test
(`SystemPromptCatalogTest.kt:698-718`) fails when a paragraph's **first
sentence** is reworded:

> `:718`: "[CoachParagraph.$paragraph] names [${paragraph.openingWords}], which
> no line of prompts/coach-system-prompt.md starts with; the index has drifted
> from the file"

So a de-duplication pass (O8) is best done by **deleting whole redundant
sentences** and by leaving every paragraph's opening sentence alone, rather than
by rewriting sentences in place. Also note `_parts/rfc-history.md`'s finding
that the redundancy is a product of the **append-only convention** — one RFC,
one self-contained appended paragraph — not of the rules themselves; four
near-identical attribution sentences exist at `:32`, `:41`, `:114`/`:135` and
`:144` because four RFCs each wrote their own.

### Test plan

Payload changes (O1-O4) are ordinary Kotlin work:

- `nix develop -c bin/test` (no module, no `-f` — let the Gradle cache scope it,
  per `CLAUDE.md`).
- Extend `CollegeCostChatToolTest` with the cases the compression must not
  break: a **mixed-publisher** college (some fields IPEDS IC_AY, some
  Scorecard), a **mixed-tier** college (an `administrative_record` net price
  beside `mandatory_survey` components), a `not_collected_by_us` field, a
  `held_academic_year` year gap, and a two-school comparison where the two
  schools are on **different** residency bases (so `by_college` must still be
  emitted under O4).
- Add a byte-budget assertion: the serialized payload for a fixed three-school
  fixture must be under a stated size. That turns "say it once" into a
  regression test rather than a hope. There is precedent for asserting on shape
  in this file; a size assertion is new.
- `public-web`'s `FakeCostReportSource` is called out in
  `CollegeCostService.kt:1975-1978` as having drifted from the real read before
  — any change to `figureStatusesOf`'s output shape must be mirrored there or
  the report page's evidence goes green against the wrong rules.

Prompt changes (O6-O9) cannot be unit-tested for quality:

- `SystemPromptCatalogTest` will verify the new row is served verbatim and that
  every prior row is still selectable. That is a **mechanism** test, not a
  behaviour test.
- Behaviour needs a transcript eval: a fixed set of money questions (single
  school, two-school comparison, out-of-state family, school with a suppressed
  net price, school with a year gap), run against vN and vN+1, scored on (a)
  word count and (b) whether each honesty guarantee in §3 still appears at least
  once per answer. I found **no such harness in the repo** — I looked for one
  and did not find it; that is an honest gap and probably the first thing brief
  0009 should build, because without it "far fewer words, losing nothing" is not
  measurable.
- Rollback: set `COACHING_SYSTEM_PROMPT_VERSION` back to the prior version. Note
  the asymmetry recorded at `service.conf:151-154` — a prompt rollback does
  **not** roll back a payload change: "the derived attribution in the payload is
  code and stays either way, so a rollback only puts the prompt back into
  disagreement with the tool." So O1-O5 (code) and O6-O9 (prompt) should land as
  **separate slices**, prompt first or code first but never fused, or the
  env-var rollback stops being a real rollback.

---

## Companion parts

- `_parts/rfc-history.md` — RFC 175/177/179/181/183/185, what each added to the
  prompt, the honesty-guarantee classification per prompt line, and the rollback
  convention verbatim.
- `_parts/other-tools.md` — measured/reconstructed payload sizes and per-row
  boilerplate for `search_colleges`, `similar_colleges`, `find_college`,
  `college_admissions_profile`, `federal_aid_policy`.
