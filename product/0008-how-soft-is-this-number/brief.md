# 0008 — How soft is this number?

Status: **GATES 1+2 APPROVED (Ian, 2026-09-05, defaults, no amendments) —
EXECUTE. Wave 1 is startable.** Slices and gate-2 decisions in `spec.md`.\
Handle: `soft` (slice IDs are `soft/NN.n/name`).\
Opened: 2026-09-05, by Ian: "Our money store treats every present figure as one
kind of fact. A Scorecard number comes from administrative loan records; a CDS
number is a school's unaudited self-report about itself, and 14 of 249 filings
in our own corpus report more borrowers than graduates. Should the store model
that softness as data — for example a `self_reported` attribute on the source —
so that the hedge in what we tell a family is derived rather than hand-written
at each site? Decide the shape and slice it."

    PHASE      FRAME -> [PRIORITISE gate 1] -> DISCOVER -> [SPEC & SLICE gate 2] -> EXECUTE -> LEARN
    NOW                                                                          ^^^^^^^ HERE

## Ledger

_(one line per landed slice: ID, RFC, SHAs, one-line what.)_

    soft/01/one-hedge-seam LANDED as RFC 177 (main@3c010785 + ebaef69e,
       2026-09-08) — money attribution derived per figure: MoneySourceCopy is the
       only English name of a publisher, FigureStatusCopy names it where the
       sentence is about the publisher's act, CostSources.SCORECARD_ATTRIBUTION is
       deleted, and coach prompt v23 stops naming the Scorecard by hand (rollback
       COACHING_SYSTEM_PROMPT_VERSION=v22). The live mis-attribution — IPEDS
       figures told to families as College Scorecard figures — is pinned by test.
       Two slice-text items returned to /chart as a spec defect: the four
       aid-policy constants and the report's merit/borrowing CDS sentence state
       CORPUS COVERAGE, not a figure's publisher.

    soft/02/assurance-tiers LANDED as RFC 179 (main@922e33c6 + 55b829f3,
       2026-09-09) — a figure now says what KIND of number it is:
       AssuranceTier (ADMINISTRATIVE_RECORD / MANDATORY_SURVEY /
       VOLUNTARY_SELF_REPORT) derived as a pure function of
       (MoneySource, source_variable), read as a pair with FigureStatus. No
       migration, no column, no seeded table, no prompt version. source_variable
       now travels to the copy seam, a SHOWN figure gets a note at last (so the
       tier is visible beside a dollar amount, not only on blank cells), and the
       Common Data Set's own read path calls the same resolver. figure_statuses[]
       gains assurance + assurance_statement. An unmapped Scorecard variable is a
       located typed fault, and the closure test derives the key space from the
       loader's own registry. Four spec corrections returned to /chart: H.208 is
       a headcount and cannot reach the seam (the softest sentence pins to
       H.209/H.211), DEBT_MDN is not a column this loader reads, DISCOVER item 9
       is stale (RFC 175 landed the H4/H5 facts), and no Scorecard data
       dictionary was committed — RFC 179 commits the 24 rows we use as
       db/seed/scorecard/dictionary-variable-sources.csv. Declined and open: a
       PublishedCell value type for the (source, source_variable) pair, asked for
       by three separate review lenses; it crosses four modules and wants its own
       slice.

## The question

The canonical money store (RFC 158) already models **why a figure is absent**:
`FigureStatus` carries six reasons, and a value exists exactly when the status
bears one. It models **who published it**: `MoneySource` now has four members,
and `CanonicalMoneyLoader.ORDERED_SOURCES` uses that axis to decide which of two
conflicting prices a family sees. It models **when**: `academic_year` /
`vintage` as start years, NULL where the source does not date the cohort. And
since RFC 170 it models **the document** a fact was read out of:
`source_documents`, one row per school per publisher per year, carrying the
school's own url and our mirror.

What it does not model is **how the number was produced, and how much weight it
will bear**. `FigureStatus.REPORTED` says "the institution reported it and the
source published it" — the same status a Scorecard figure drawn from federal
administrative loan records carries and a Common Data Set cell a school typed
about itself carries. The two are not the same kind of fact, and the difference
is exactly the thing a family needs said out loud.

Today the difference IS said — in hand-written prose, at each site. Coach prompt
v20 spends whole paragraphs teaching the model when a number is the school's own
statement and when it is ours; `CdsCitation` writes a CDS-shaped citation;
`CostReportPage` writes its own words; the iOS surfaces write theirs. Every new
reader must re-derive the hedge from scratch and can silently get it wrong — the
failure mode RFC 170's review already caught three times in one run (a school
whose filing we hold told "we hold no filing"; our own gap rendered as the
school's silence).

The bet under this brief: **provenance softness is data, not copy.** If the
store carries it, one renderer derives the sentence and every surface — chat,
report, app, search — inherits the same honesty for free.

## What the repo does today (grepped, not assumed)

| Thing                      | Where                                                         | What it carries                                                                                              |
| -------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `MoneySource` (4 members)  | `db/.../models/MoneySource.kt`, `money_source` DOMAIN (0087)  | publisher identity only. Explicitly "external identity and provenance", a code list, NOT a vocabulary table. |
| `FigureStatus` (6 members) | `db/.../models/FigureStatus.kt`, `figure_statuses` vocabulary | why a value is present or absent. `REPORTED` conflates administrative record with self-report.               |
| `source_documents`         | `db/schema/0087...`                                           | one filing per school/publisher/year, `source_url` + `archive_url`. No quality or assurance attribute.       |
| precedence                 | `CanonicalMoneyLoader.ORDERED_SOURCES`                        | which source wins a conflict. A hard-coded order, deliberately not operator-editable.                        |
| the hedge                  | coach prompt v20, `CdsCitation`, `CostReportPage`, iOS copy   | hand-written prose, per site, no shared derivation.                                                          |
| CDS corpus                 | `db/seed/cds/*.csv` + `PROVENANCE.json`                       | the filings Ian's "14 of 249" claim is measured over.                                                        |

## Candidate shapes

| #  | Shape                                                                                                                                       | Existing foundation                                         | Cost                                                                                              |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| C1 | **`selfReported: Boolean` on `MoneySource`** — a code-side attribute, no migration. Renderer branches on it.                                | `MoneySource` already carries per-member Kotlin metadata.   | Cheapest. But binary, and IPEDS is _also_ institution-reported.                                   |
| C2 | **An `assurance` axis on the source**: administrative record / mandatory audited survey / voluntary self-report. Three tiers, one sentence. | Same place as C1; the owned-enumeration rule applies.       | One more enum, no migration if it stays code-side.                                                |
| C3 | **Assurance on the DOCUMENT, not the publisher** (`source_documents`) — a filing can be checked; a publisher cannot.                        | `source_documents` exists and is already the citation root. | A migration and a column every filing must be given a value for.                                  |
| C4 | **Stored coherence findings** — ingest-time internal-consistency checks (borrowers > graduates) written as rows against a figure or filing. | The ingest phases and `college_index_build` provenance.     | New table; Ian approves DDL at the gate. The only shape that can catch a school that lied _once_. |
| C5 | **Derived hedge renderer only** — no new data; one shared function that turns (source, status, year) into the sentence every surface uses.  | `CdsCitation` is the seed of exactly this.                  | Kills the duplication without deciding the ontology.                                              |
| C6 | **Hybrid** — C2 (static assurance tier) + C4 (per-filing coherence flags) + C5 (one renderer). Softness as data; hedge derived once.        | All of the above.                                           | The largest, and the only one that answers both halves of Ian's question.                         |
| C0 | **Do nothing structural** — keep the hedge in prompt copy, tighten the words.                                                               | Status quo.                                                 | Zero cost, and the RFC 170 failure mode stays live.                                               |

## Success criteria for the decision

1. **The hedge is derived, not retyped.** After the chosen bet lands, adding a
   fifth publisher or a new reader surface must not require anyone to write a
   new hedge sentence by hand.
2. **The axis is true.** Whatever we store must survive the fact that IPEDS is
   also institution-reported — a `self_reported` boolean that calls IPEDS soft
   and the Scorecard hard is a lie of a different kind. The distinction that
   actually matters (mandatory + edit-checked vs voluntary + unaudited vs
   administrative record) is what research must establish.
3. **A family hears a difference.** The value is user-visible: a CDS-sourced
   sentence must read differently from a Scorecard-sourced one, in chat AND in
   the report, without the coach improvising.
4. **The store never invents confidence.** No numeric score, no "reliability
   percentage" we cannot defend from the publisher's own documentation.
5. **Bounded.** The bet fits in the money brief's grain — a small number of
   slices, none of which blocks `shape/05` or `shape/08`.

## Open assumptions research must settle

- Is "14 of 249 filings report more borrowers than graduates" true of the corpus
  we actually hold, and what else is checkable in it?
- Is IPEDS meaningfully more assured than the CDS, from the publishers' own
  documentation — mandatory reporting, edit checks, keyholder attestation — or
  is that a story we tell ourselves?
- Which Scorecard figures are administrative (NSLDS, IRS) and which are just
  IPEDS re-published? A source-level tier is wrong if one publisher mixes both.

## Research synthesis (three parallel reports, 2026-09-05)

Files: `research/repo-hedge-inventory.md`, `research/source-assurance.md`,
`research/corpus-coherence.md`.

**The three reports converge on one answer and they kill two of the candidates
independently.**

| Rank | Bet                                                      | repo-hedge-inventory                                                               | source-assurance                                                | corpus-coherence                                                    | Verdict                        |
| ---- | -------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------- | ------------------------------ |
| 1    | **C5 — derive the hedge in one seam**                    | one address exists: `FigureStatusCopy.kt:50` maps `REPORTED` -> null, untested arm | needed regardless of tiers                                      | "unconditionally"                                                   | **DO FIRST, ALONE**            |
| 2    | **C2' — assurance tier, re-keyed off `source_variable`** | cheap, no migration; `MoneySource` is dropped before any renderer sees it          | tiers are real and defensible from NCES's own words             | must not key on `MoneySource`                                       | **DO SECOND**                  |
| —    | C1 — `selfReported: Boolean` on the source               | `MoneySource` carries no metadata today                                            | 16 of the 18 Scorecard cells we load are re-published IPEDS     | same finding, independently                                         | **REJECT — the axis is false** |
| —    | C4 — stored coherence findings table                     | no reader exists (RFC 170's speculation bar)                                       | per-document flags are the only way to separate two CDS filings | the table would be **empty**: every internal check runs 0/309 today | **REJECT as a table, for now** |
| —    | C3 — assurance on `source_documents`                     | `source_documents` is CDS-only today                                               | per-document is right eventually                                | revisit when `shape/07b/borrowing` lands                            | **DEFER**                      |
| —    | C0 — do nothing                                          | a live mis-attribution defect is shipping now                                      | FTC s.5 has no innocent-repetition exemption                    | —                                                                   | **REJECT**                     |

### The three findings that decide the shape

**1. A per-source softness attribute would be a lie.** The College Scorecard
data dictionary carries a per-variable `SOURCE` column. Of the 18 cells
`CanonicalMoneyLoader` actually reads, **16 are re-published IPEDS**; only
`GRAD_DEBT_MDN`/`DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10` (Treasury) are
administrative records, and they have no IPEDS twin. Our single `SCORECARD`
member therefore spans two assurance tiers. Both the source-assurance and
corpus-coherence children reached this independently. **C1 dies here, and C2
survives only re-keyed.**

**2. But the axis itself is real, from the publishers' own documentation.**
IPEDS reporting is mandatory under 20 USC 1094(a)(17) with FSA fines, has ~100%
response, on-form edit checks against the prior year including cross-item
coherence, a keyholder lock attesting the data is "accurate, true, and
complete", and per-cell imputation flags **we already ingest**
(`IpedsImputationFlag.kt`). The Common Data Set template has no collector, no
deadline, no sanction, and zero occurrences of "audit" or "verif*"; every known
misreport (Columbia, Temple, Emory, GW, Claremont McKenna, USC Rossier) was
caught by an outsider, not by the CDS.

**3. Ian's 14-of-249 is exactly true — and it is not evidence about schools.**
Reproduced to the digit against the collegedata.fyi corpus (H.401 vs H.501). But
**12 of the 14 are our own PDF-extraction failures**; two literally carry the
class year ("2024", "2025") as the graduating-class count. The number measures
**our extractor**, not a school's honesty. Worse, those facts are not in the
shipped seed at all — RFC 170 D1 split H4/H5 out to the unlanded
`shape/07b/borrowing`. And every internal-consistency rule the child could
define runs to **zero** on the shipped corpus, because `bin/fetch-cds-seed`
already applies those rules at ingest and **drops** the block instead of storing
a flag. A C4 table built today would be empty, and a flag rendered from it would
tell a family "this school looks wrong" when the truth is "we could not read
this filing" — the precise RFC 170 failure mode.

### Two live defects the research surfaced

- **Mis-attribution, shipping now.** Coach prompt v20:107-110 ("Always attribute
  cost figures to the College Scorecard") and
  `CostSources.SCORECARD_ATTRIBUTION` (`SingleSchoolBasis.kt:225`) both
  contradict `ORDERED_SOURCES` (`CanonicalMoneyLoader.kt:1117`), which puts
  IPEDS first. IPEDS figures are attributed to the wrong publisher in chat and
  on the report page today. This is the brief's thesis demonstrated: the hedge
  is hand-written, so it drifted off the data.
- **Vintage stamping.** `CanonicalMoneyLoader` stamps AY2024-25 Scorecard
  charges as `AcademicYear(2022)` (:908, :1152) and AY2023-24 NPT4 as 2021
  (:986, :1003, :1155). RFC 161's designed displacement never fires and the NPT4
  rows collide with SFA 2021-22. It also explains why RFC 161's 92.1%
  `TUITIONFEE_IN` agreement now measures 20.3% — a one-year vintage gap (median
  ratio 1.0262 against IPEDS's own YoY 1.0265), not a source dispute.

### Corrections to FRAME (recorded, not edited above)

- iOS writes **no** hedge copy — 124 Swift files, 0 assertions; it is
  pass-through markdown and inherits a derived hedge for free.
- `MoneySource` carries **no** per-member metadata; it has only
  `val value: String`.
- Both `price_figures` and `cohort_money_stats` already carry
  `source_variable TEXT NOT NULL`, so an assurance tier keyed on
  `(source, source_variable)` is computable **at read time, with no migration
  and no new column** — which is also what the repo's derived-figures rule and
  RFC 161:181-182 (no operator-editable softness table) require.
- The Columbia settlement is July 2025, not 2023.
- 26 tests across 7 files pin hedge words; the `REPORTED` arm is untested, so
  slice 1 breaks none of them.

## GATE 1 — prioritisation

Defaults are pre-chosen. "Approve" takes them all.

**D1. The bet.** Adopt **C5 then C2'**: derive the hedge in one seam first, then
add the assurance tier behind that seam. Reject C1 (false axis) and C0. Defer
C3. Do not build C4 as a table now. _Default: yes._

**D2. Where assurance attaches.** The tier is a pure function of
`(source, source_variable)` — the pair both fact tables already store — resolved
in code at read time. **No migration, no column, no seeded table.** _Default:
yes._

**D3. The tiers.** Three, named: `ADMINISTRATIVE_RECORD` (NSLDS, Treasury),
`MANDATORY_SURVEY` (IPEDS IC_AY and SFA, and the 16 Scorecard cells that are
re-published IPEDS), `VOLUNTARY_SELF_REPORT` (Common Data Set). Read as a
**pair** with the existing `FigureStatus`, which stays orthogonal. _Default:
yes._

**D4. Coherence stays at ingest.** No findings table. Add the two cross-source
rules that actually fire (CDS freshman count vs IPEDS `SCFA1N`; duplicate filing
bytes) to `bin/fetch-cds-seed`'s existing drop-and-fall-back set. Revisit C3/C4
only when `shape/07b/borrowing` lands and the H4/H5 facts exist. _Default: yes._

**D5. Fix the mis-attribution inside slice 1**, including a coach prompt **v21**
(the prompt names the Scorecard by hand and must stop). _Default: yes._

**D6. The vintage bug is a slice in THIS brief**, not a brief-0006 amendment: it
corrupts the year a derived citation would state, so the hedge work owns it.
_Default: yes._

**D7. Standing: no confidence numbers, ever.** unicoach never renders a score, a
percentage of reliability, or a star rating for a figure. The hedge is a
sentence naming who produced the number and how, in the publisher's own register
— "estimated by the institution", "the school's own unaudited report". _Default:
yes — and mark `standing`, so later briefs inherit it._

**Gate 1 outcome: APPROVED by Ian, 2026-09-05 — all defaults, no amendments.**
D7 is marked `standing`: later briefs inherit "no confidence numbers, ever"
without re-asking.
