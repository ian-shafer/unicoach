# 0008 — spec & slices

Gate 1 approved by Ian, 2026-09-05, defaults, no amendments (D1-D7 in
`brief.md`). This file is the SPEC & SLICE artifact: four slices and the gate-2
decisions.

Handle: `soft`. Slice IDs are `soft/NN/name` and are never renumbered.

## DISCOVER — what is actually true in the tree (corrections the slices assume)

Grounded by the three research reports; every claim below is cited there.

1. **`MoneySource` never reaches a renderer.** 25 Kotlin files mention it, and
   **zero** in `service/src/main/**` or `public-web/src/main/**`. The read row
   carries it (`CanonicalMoneyReadRows.kt:28,48`) and the service layer drops
   it. Every candidate shape is, at code level, first "thread `source` through".
2. **The hole has one address.** `FigureStatusCopy.kt:50` maps
   `FigureStatus.REPORTED -> null`. A CDS self-report and a Scorecard NSLDS
   figure both land there and get no sentence. **That arm has zero test
   coverage**, so adding to it breaks none of the 26 pinned-word assertions.
3. **A live mis-attribution ships today.** Prompt v20:107-110 ("Always attribute
   cost figures to the U.S. Department of Education College Scorecard") and
   `CostSources.SCORECARD_ATTRIBUTION` (`SingleSchoolBasis.kt:225`) contradict
   `ORDERED_SOURCES` (`CanonicalMoneyLoader.kt:1117`), which puts both IPEDS
   sources first. IPEDS figures are named as Scorecard figures in chat and on
   the parent-facing report page.
4. **Both fact tables already carry `source_variable TEXT NOT NULL`**
   (`0083:194`, `0083:259`). So an assurance tier keyed on
   `(source, source_variable)` is computable at read time with **no migration,
   no column and no seeded table** — which is what the repo's derived-figures
   rule and RFC 161:181-182 require.
5. **iOS writes no hedge copy.** 124 Swift files, 4 grep hits, all doc comments,
   0 test assertions. `ConversationView.swift:296,302` is pass-through markdown.
   iOS inherits a derived hedge with no Swift change.
6. **`MoneySource` carries no per-member metadata** — only `val value: String`.
   Slice 2 introduces the first one. There is also no `sourceName(MoneySource)`
   anywhere: **no spoken publisher label exists as data**.
7. **The prompt is already 79% derived.** Of 39 hedge sites in v20, 31 key on
   payload structure and are publisher-agnostic; exactly 5 hard-code a publisher
   and 3 more silently lose the distinction. v20 is v19 plus one appended
   paragraph, and **3 of its 4 new sentences restate rules already in the same
   prompt** — the duplication this brief exists to end.
8. **`publisher_flag`** (`price_figures`, `cohort_money_stats`) already holds
   the raw IPEDS X-code, but is NULL for the Scorecard and the CDS. It is not a
   universal axis and slice 2 must not pretend it is.
9. **Every purely internal CDS coherence check runs to zero** on the shipped
   seed, because `bin/fetch-cds-seed:949-960,1267-1299` already applies those
   rules and **drops** the block. The H4/H5 facts behind "14 of 249" are not in
   the seed at all — RFC 170 D1 split them to the unlanded
   `shape/07b/borrowing`.
10. **The vintage defect.** `CanonicalMoneyLoader` stamps AY2024-25 Scorecard
    charges as `AcademicYear(2022)` (`:908,:1152`) and AY2023-24 `NPT4` as 2021
    (`:986,:1003,:1155`). Consequences, both measured: RFC 161's designed IC_AY
    displacement **never fires** (tuition keys never collide), and the Scorecard
    `NPT4` rows **do** collide with SFA 2021-22, so a 2021-22 net price can
    displace a 2023-24 one under a 2021-22 label. Scorecard/IPEDS tuition
    agreement is 20.3%, not RFC 161's 92.1%, with a median ratio of 1.0262
    against IPEDS's own year-over-year 1.0265 — one year of inflation, not a
    dispute.

### Standing bars every slice must clear (from the RFC corpus)

- **Speculative until a reader exists** (`rfc/170:153`) — which is why slice 1
  lands before slice 2.
- **Anti-vacuity** (`rfc/166:507-509`): "a routing function that returns the
  same reason from all of its arms is not a rule; it is dead code that reads
  like one."
- **Never manufacture a value the source never published** (`rfc/170:60-66`).
- **Softness must not become a seeded, operator-editable table**
  (`rfc/161:181-182`).
- **D7 (standing, gate 1):** no confidence score, percentage or rating, ever.

---

## Wave board

| Wave | Slices                          | Why they are safe together                                                      |
| ---- | ------------------------------- | ------------------------------------------------------------------------------- |
| 1    | `soft/01`, `soft/03`, `soft/04` | Disjoint trees: service+prompt / `CanonicalMoneyLoader` / `bin/fetch-cds-seed`. |
| 2    | `soft/02`                       | Needs slice 1's seam as its only reader.                                        |

---

## `soft/01/one-hedge-seam`

**Needs:** — (nothing: the seam, the read row and the source column all exist
today; no other slice's output is consumed.)\

**What to build and why.** One place in the code decides how a money figure is
described, and every surface asks it. Today the chat prompt, the chat tool and
the report page each write their own words about where a number came from, and
they have already drifted apart — we tell families that an IPEDS figure came
from the College Scorecard. This slice makes the attribution **derived per
figure** and deletes the hand-written publisher names.

**Foundations to build on.** `FigureStatusCopy.statementOf` (RFC 166 §6 — the
same pattern already landed for the ABSENCE axis, including
`SystemPromptCatalogTest`'s enum walk that keeps prompt and wire copy from
drifting); `CanonicalMoneyReadRows.source`; `CdsCitation` (RFC 148 — holding the
parts, never a bare year); `ORDERED_SOURCES` as the truth about who won.

**Decided here.**

- `MoneySource` gains a **spoken label** (there is none today) and it is the
  only place a publisher is named in English.
- `FigureStatusCopy` is widened to take the source alongside the status; its
  `when` stays exhaustive with **no `else`**, so every arm is re-decided at
  compile time.
- `CostSources.SCORECARD_ATTRIBUTION`, the four hard-coded "Common Data Set"
  constants (`AidPolicyWire.FORMS_NOTE`, `FORMS_NOT_COLLECTED_NOTE`,
  `AID_POLICY_NO_FILING`, `AID_POLICY_NO_FACT_IN_FILING`) and
  `CostReportPage.sourcesSection()`'s two-publisher literal are replaced by
  derived attribution. `sourcesSection()` currently takes **no arguments**; it
  must take what it describes.
- Coach prompt **v21**: the 5 publisher-naming hedge sites become
  publisher-agnostic — "attribute each figure to the source the tool names with
  it" — and the prompt stops naming the Scorecard by hand. Rollback
  `COACHING_SYSTEM_PROMPT_VERSION=v20`.

**Left to /ship's design phase.** The exact signature and call graph of the
widened seam; whether the spoken label lives on `MoneySource` or in a small
renderer beside `FigureStatusCopy`; how the report page threads what it needs
into `sourcesSection()`.

**Acceptance criteria.**

- A figure whose winning source is IPEDS is **never** attributed to the College
  Scorecard, in chat or on the report page. A test asserts this on a college
  where `ORDERED_SOURCES` picks IPEDS — the live defect, pinned.
- No `service/src/main/**` or `public-web/src/main/**` file contains a
  hard-coded publisher name any more. Grep is the test.
- Adding a fifth `MoneySource` member forces a compile error at the seam, not a
  silently wrong sentence.
- The `REPORTED` arm gains coverage: it is untested today.
- **Reachability:** the door is the existing one — any family asking the coach
  what a school costs, and any parent opening a shared Family Cost Report. No
  new surface, so nothing is unreachable.
- **Value before ask:** nothing is asked of the user; this slice only changes
  what we say. No new prompt, no new step.
- **No new table, no migration** other than the prompt seed. DDL gate not
  engaged.
- Test-count evidence: report executed counts, and name which of the 26
  pinned-word assertions changed and why.

**First-session test.** A student asks "what does Austin Community College
cost?" in their first session and is told the in-district price with the right
publisher named beside it.

---

## `soft/02/assurance-tiers`

**Needs:**

- BLOCKS soft/01/one-hedge-seam — the tier's only reader is the widened
  `FigureStatusCopy` seam; landing the tier first would be data with no reader,
  which `rfc/170:153` refuses.

**What to build and why.** Teach that one seam the difference between a number
from federal records, a number a school was legally required to file and have
edit-checked, and a number a school typed about itself that nobody checks. Then
a family hears an honest difference without anyone writing the sentence again.

**Foundations.** `(source, source_variable)`, both stored `NOT NULL` on both
fact tables; `FigureStatus`, which stays orthogonal;
`IpedsImputationFlag`/`publisher_flag` for the per-cell imputation fact we
already ingest.

**Implementation note, recorded 2026-09-08 after RFC 177 landed (Ian asked for
it during `soft/01`'s close-out; no decision here is changed, and this is a fact
about the tree, not a re-specification).** `soft/01` threaded the winning
publisher — and **only** the publisher — from the store to the copy seam.
`source_variable` is on the read rows (`CanonicalMoneyReadRows.kt:29,49`) and
reaches **zero** files under `service/src/main/**`. So the second half of this
slice's key still has to travel:

- the carriers `DatedFigure`, `DatedStat`, `FigureProvenance` and
  `FigureStatusNote` each carry `source: MoneySource` and must also carry the
  variable;
- the seam is `FigureStatusCopy.statementOf(status, source)` today and becomes a
  triple once the tier is a function of `(source, source_variable)`;
- the publisher's own English name already exists as `MoneySourceCopy`, so the
  tier's sentences compose with it rather than repeating a publisher name;
- `agentlessStatementOf` is the byte-frozen one-argument form the immutable v19
  prompt row recites — it must keep its exact words, so a tier sentence is added
  beside it, never inside it.

The threading is the same shape `soft/01` already did once, one field over. It
is work this slice inherits, not a new decision.

**Decided here (gate 1, D2/D3).**

- Three tiers: `ADMINISTRATIVE_RECORD`, `MANDATORY_SURVEY`,
  `VOLUNTARY_SELF_REPORT`.
- The tier is a **pure function of `(source, source_variable)`**, resolved in
  code at read time. **No migration, no column, no seeded table, not on
  `MoneySource`.** The reason it cannot hang off `MoneySource`: of the 18
  Scorecard cells the loader reads, **16 are re-published IPEDS** and only
  `GRAD_DEBT_MDN`/`DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10` (Treasury) are
  administrative records.
- The renderer reads **`(assurance, status)` as a pair**. An imputed IPEDS cell
  is soft in a different way from a CDS cell and must not collapse into it.
- The sentences carry no number, score or rating (D7). Starting wording, at an
  8th-grade reading level:
  - `ADMINISTRATIVE_RECORD` — "This comes from federal loan and tax records, not
    from the college. It covers only students who got federal aid, and small
    numbers are blurred a little to protect privacy."
  - `MANDATORY_SURVEY` — "The college had to report this to the U.S. Department
    of Education, and the form checks it against last year's answer. Nobody
    audits it."
  - `VOLUNTARY_SELF_REPORT` — "The college published this about itself. No one
    checks it. We link to the college's own file so you can see it."
  - `IMPUTED_BY_PUBLISHER`, on top of any tier — "The college did not answer
    this one, so the government filled it in from similar colleges."
- **"Average percent of need met" (CDS H.208/H.209) gets the softest sentence in
  the vocabulary**: it is self-reported, unaudited, and unverifiable against any
  federal file that exists.

**Left to /ship.** Wording polish (may improve the English; may not add a
number); where the mapping table lives; how the CDS-only measures are covered.

**Acceptance criteria.**

- Every `source_variable` the loader can write resolves to exactly one tier, and
  an unmapped variable is a **fatal**, on the `ORDERED_SOURCES` unranked-member
  precedent — never a silent default to the softest or hardest tier.
- A **falsifiable pin on the tier assignment**: a test asserts, against a
  committed copy of the College Scorecard data dictionary, that of our 18 loaded
  columns exactly 16 carry `SOURCE == IPEDS` and the other two are NSLDS /
  Treasury. If NCES re-sources a column, this test fails and the tier is wrong.
- **Anti-vacuity:** a test proves the three arms produce three different
  sentences on real rows, and that at least one figure a family actually sees
  lands on each tier.
- No DDL. If /ship's design wants a column or a table, it comes back to this
  brief — that is a spec defect, not a /ship decision.
- **Reachability:** the same door as slice 1. Name in the RFC the specific chat
  answer and the specific report-page line where each of the three sentences
  first becomes visible.
- Value before ask: nothing asked of the user.

**First-session test.** A student asks "does Amherst meet full financial need?"
and hears the answer with "the college published this about itself; no one
checks it" attached — not as a warning box, as part of the sentence.

---

## `soft/03/the-year-we-cite`

**Needs:**

- CONFLICTS soft/02/assurance-tiers — both edit
  `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`; rebase
  risk only, not an order. Either may go first.

**What to build and why.** We stamp the wrong academic year on Scorecard
figures, and a derived citation is only as honest as the year it states. This is
gate-1 D6: the hedge work owns it because it corrupts what the hedge would say.

**The defect, measured.** Scorecard charges are stamped `AcademicYear(2022)`
(`:908,:1152`) but describe AY2024-25; `NPT4` is stamped 2021
(`:986,:1003,:1155`) but describes AY2023-24. Because `PriceKey`/`StatKey`
include the year: (a) IC_AY never collides with Scorecard tuition, so **RFC
161's designed displacement never fires**; (b) Scorecard `NPT4` rows **do**
collide with SFA 2021-22, so a 2021-22 net price can displace a 2023-24 one
under a 2021-22 label.

**Acceptance criteria.**

- The stamped year equals the year the Scorecard's own dictionary says the
  variable describes, pinned by test against the committed dictionary — not a
  literal re-typed in Kotlin.
- The RFC **counts and reports how many served figures change**, in both
  directions: this slice will change prices families see. Austin Community
  College (2550 / 2550 / 8580) is the named worked example.
- RFC 161's displacement is shown firing, with a before/after count. RFC 161's
  "92.1% agreement" claim is restated honestly for the pinned artifacts (20.3%,
  cause: a one-year vintage gap, median ratio 1.0262 against IPEDS's own 1.0265)
  — a correction in a new RFC, never an edit of 161.
- No migration expected; if the ingest must be re-run to take effect, the RFC
  says so in one line and the ledger records it.
- **Reachability:** every cost answer already reaches a family; this changes the
  numbers and the years inside them.

---

## `soft/04/two-more-ingest-refusals`

**Needs:** — (nothing: `bin/fetch-cds-seed` already owns the drop-and-fall-back
rules this extends, and no other slice touches it.)\

**What to build and why.** Gate-1 D4. Coherence checking stays where an operator
reads it, not where a family does. Two cross-source rules this research found
actually fire, and **8 wrong numbers are shipping today** because neither
exists.

**Decided here.**

- Add to `bin/fetch-cds-seed`'s existing refusal set (`:949-960`, `:1267-1299`),
  in the same drop-and-fall-back shape as the nine reasons already there:
  1. **Freshman count against the federal file** — refuse a filing whose `H.201`
     exceeds IPEDS `SCFA1N` by more than 50%. Measured: 20 of 270 filings (7.4%)
     fail, 8 of them provably because the extractor grabbed total undergraduate
     enrolment; an independent replication against ADM2023 found 14 of 263, all
     inside that 20.
  2. **One filing attributed to several `unit_id`s** — refuse a value-tuple that
     repeats byte-for-byte across different `unit_id`s in the same
     `source_year`. Measured: 7 tuples touching 15 unit_ids (3.6% of 421), a
     main campus's PDF mapped onto branch UNITIDs. Two of them have no IPEDS SFA
     row at all.
- Count both new reasons in `PROVENANCE.json` beside the nine existing ones.

**Explicitly NOT built.** No findings table, no per-figure flag, no
family-visible "this school looks wrong". Gate-1 D4. A refusal here means "we
could not read this filing", which is our gap and must never be spoken as the
school's.

**Acceptance criteria.**

- Both rules refuse the measured filings and the counts land in
  `PROVENANCE.json`; the RFC states the before/after seed row counts.
- A refused block falls back exactly as the nine existing reasons do — no new
  failure mode, and the ingest does not exit green having erased CDS facts (the
  RFC 170 review finding).
- `bin/` conventions hold: `getopts` only, all logging to stderr.
- **Reachability:** indirect and honest — a family stops being told a wrong
  freshman count. The RFC names the 8 colleges whose numbers change.
- Shell assertions are part of the gate: run `nix develop -c bin/shell-tests`
  and report executed counts.

---

## GATE 2 — slices and decisions

Defaults are pre-chosen. "Approve" takes them all.

**G1. Slice set and order.** Four slices. Wave 1 = `soft/01` + `soft/03` +
`soft/04` (disjoint trees, safe in parallel); wave 2 = `soft/02`. The aha is
`soft/02`, but `soft/01` is where the live defect dies. _Default: yes._

**G2. Slice 1 ships coach prompt v21** and rewrites the 5 publisher-naming hedge
sites to be publisher-agnostic. Several `SystemPromptCatalogTest` assertions
change; that is expected and must be reported, not worked around. _Default:
yes._

**G3. The tier stays code-side.** No migration, no column, no vocabulary table,
in this brief. If /ship's design argues it must be stored, it returns here as a
spec defect. _Default: yes._

**G4. Wording.** The four sentences above are the starting text. /ship may
improve the English; it may not add a number, score or rating (D7), and it may
not turn a hedge into a warning box. _Default: yes._

**G5. The tier assignment is pinned to the publisher's own dictionary** — a
committed copy of the Scorecard data dictionary, asserted 16 IPEDS / 2
administrative. A tier we cannot read from a publisher's documentation is a
manufactured claim (`rfc/170:60-66`). _Default: yes._

**G6. No new tables anywhere in brief 0008**, so the DDL-at-gate rule
(non-negotiable 6) is not engaged by any slice. Any slice that discovers it
needs a table stops and comes back. _Default: yes._

**G7. `soft/03` will change prices families see.** Land it anyway, with the
count of changed figures in the RFC and the ledger line. _Default: yes._

**G8. C3 and C4 stay deferred**, revisited only when `shape/07b/borrowing` lands
and the H4/H5 facts exist. Recorded here so the deferral is visible rather than
forgotten. _Default: yes._

---

**Gate 2 outcome: APPROVED by Ian, 2026-09-05 — all defaults (G1-G8), no
amendments.** Dispatch order recorded at approval: wave 1 starts with
`soft/01/one-hedge-seam` and `soft/04/two-more-ingest-refusals`;
`soft/03/the-year-we-cite` is held until `shape/07b/borrowing` (in flight as
rfc-175) lands, to avoid a three-way rebase on `CanonicalMoneyLoader.kt`. That
is a scheduling choice, not a `Needs:` edge, and the board still reads `soft/03`
as READY.
