# Part C — Prior RFC decisions on the softness / provenance axis

Scope: `rfc/*.md` in the live tree only (172 files; `.claude/worktrees/**`
ignored). Every count below is from `grep` run at repo root. Read-only; no code,
schema or doc outside this file was touched.

## C.0 Headline: the axis has never been decided, because it has never been named

Term-frequency over all 172 RFC files (`grep -rio "<term>" rfc/*.md | wc -l`):

| term                                                      | hits in `rfc/*.md` | what the hits actually are                                                                                                                                                                                      |
| --------------------------------------------------------- | ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `self-report`, `self_report`, `selfReport`, `self report` | **0**              | —                                                                                                                                                                                                               |
| `unaudited`                                               | **0**              | —                                                                                                                                                                                                               |
| `softness`                                                | **0**              | —                                                                                                                                                                                                               |
| `hedge` / `hedging`                                       | **0**              | —                                                                                                                                                                                                               |
| `voluntar*`                                               | **0**              | —                                                                                                                                                                                                               |
| `keyholder`                                               | **0**              | —                                                                                                                                                                                                               |
| `edit check`                                              | **0**              | —                                                                                                                                                                                                               |
| `mandatory report*`                                       | **0**              | —                                                                                                                                                                                                               |
| `administrative record`                                   | **0**              | —                                                                                                                                                                                                               |
| `own statement`                                           | **0**              | —                                                                                                                                                                                                               |
| `we do not hold`                                          | **0**              | —                                                                                                                                                                                                               |
| `assurance`                                               | **1**              | `rfc/01-rest-api-stack.md:62` `## 4. Quality Assurance & Testing Strategy` — software QA, not data assurance                                                                                                    |
| `audited`                                                 | **3**              | `rfc/87-…:32` `### 1. Audited current state`; `rfc/154-…:10,134` "This slice audited every call site" / "an AUDITED FINDING" — code review, not data                                                            |
| `attest*`                                                 | **3**              | `rfc/54-client-key-gate.md:12` "App Attest (device attestation)"; `rfc/78-…:169` "out-of-domain `CONTROL` is unattested"; `rfc/172-…:195` "strictly better attested" (a fetch manifest vs an operator's memory) |
| `mandatory`                                               | 19                 | all software/config obligations (`convos.name` is mandatory, mandatory test steps, …), none about a reporting regime                                                                                            |

`provenance` appears in 28 RFC files, but in this repo the word means **ingest
provenance** — snapshot URL / sha256 / date / row counts on
`college_index_build`
(`rfc/139-fuzzy-college-search-and-ingest-provenance.md:23,194,208,234`;
`rfc/144-…:95,100,105`; `rfc/147-…:76,241`). It has never meant "how much weight
this figure will bear". The one exception in wording is RFC 161's line that a
source is "external identity and provenance, not a concept" (`rfc/161:180-181`),
quoted in full below — and that line is used to _refuse_ enrichment, not to
invite it.

**Falsifiable claim:** no committed RFC states, defers, or rejects a decision
about modelling assurance/self-report as data. The axis is not "deferred"; it is
unraised. Several RFCs, however, argue about the _adjacent_ axes in ways that
bind C1–C6.

---

## C.1 The four decisions the parent named

### RFC 158 — "The canonical money store" — decision **D3**

D3 is a **brief 0006 gate-1 decision**, not an RFC-158-local decision (RFC 158's
own decisions are numbered `P1`–`P12`,
`rfc/158-canonical-money-store.md:19-134`; it cites D3 three times at `:95`,
`:184`, `:238`). Verbatim, from
`product/0006-money-in-unicoach-shape/brief.md:310-318`:

> **D3. Missingness is a stored status, not a bare NULL.** Per figure:
> `reported` / `not_reported_by_institution` / `not_applicable` /
> `suppressed_by_publisher` / `imputed_by_publisher` / `not_collected_by_us` —
> with a value-IFF-(reported|imputed) CHECK, the `money_profiles` tri-state
> precedent generalized (AUDIT §1.5). This is fillable: IPEDS X-flags natively
> distinguish reported/blank/not-applicable/imputed; Scorecard's
> `PrivacySuppressed` is real and currently discarded at
> `CollegeScorecardLoader.kt:1239` (SOURCES, missingness table). Recovering it
> requires re-parsing the pinned snapshots — an ingest change, no new source
> fetch. **DEFAULT: yes.**

RFC 158 lands it as a vocabulary table plus a CHECK
(`rfc/158-canonical-money-store.md:177-186`):

> ```sql
> CREATE TABLE figure_statuses (
>     slug          TEXT        NOT NULL PRIMARY KEY,
>     description   TEXT        NOT NULL,
>     value_bearing BOOLEAN     NOT NULL,
> ```
>
> ```
> -- rows (D3): reported (TRUE), imputed_by_publisher (TRUE),
> --   not_reported_by_institution, not_applicable, suppressed_by_publisher,
> --   not_collected_by_us (all FALSE)
> ```

and `rfc/158:236-241`:

> ```
> -- D3: a value exists exactly when the status bears one. The two
> -- value-bearing statuses are named literally (a CHECK cannot subquery
> -- figure_statuses.value_bearing); a test pins the two lists together.
> ```

**What it decided about the softness axis:** D3 models the axis **"why is the
value absent, and whose fault is that"** — and _only_ that. Six statuses, all
about presence/absence; `reported` is the single terminal value-bearing status
for anything a publisher actually published, regardless of how it was produced.
The brief 0008 claim that `REPORTED` conflates an NSLDS administrative figure
with a typed CDS cell is **correct and traceable to D3's own wording** — the six
values are exhaustive over missingness reasons and carry no production/assurance
dimension. D3 does **not** defer assurance; it never contemplates it.

Note the one structural gift D3 gives shape C2/C3: `figure_statuses` is a real
vocabulary TABLE with a `description` and a `value_bearing` boolean — precedent
that a per-code attribute can ride on a vocabulary row. That precedent is _not_
available on `MoneySource`, per RFC 161 below.

### RFC 161 — "IPEDS IC_AY canonical charges" — the money-source decision (labelled **"decision 6"** in the RFC, i.e. brief 0006 D6)

RFC 161 has **no `D6` heading of its own** (`grep -n "^\*\*D[0-9]" rfc/161-*.md`
→ no matches; its sections are `## Summary`, `## Two corrections…`,
`## Detailed Design`, …). The relevant unit is
`### 2b. The money source becomes an owned enumeration (decision 6)` at
`rfc/161-ipeds-ic-ay-canonical-charges.md:156`. Verbatim, `:156-182`:

> ### 2b. The money source becomes an owned enumeration (decision 6)
>
> `price_figures.source` is `TEXT` with only a non-empty CHECK, and one Kotlin
> constant `SOURCE_SCORECARD = "scorecard"`. That was harmless while one source
> existed and the column was decorative. This RFC makes it load-bearing —
> `ORDERED_SOURCES` decides which of two conflicting prices a family is shown —
> so a mistyped source string would silently change a price with nothing in the
> schema to notice. The repo's own rule applies: an owned enumeration is
> `TEXT` + `CHECK IN (...)` plus exactly one Kotlin `enum class` with a
> `fromValue` companion.
>
> ```sql
> ALTER TABLE price_figures
>   ADD CONSTRAINT price_figures_source_domain_check
>   CHECK (source IN ('ipeds_ic_ay', 'scorecard'));
> ALTER TABLE cohort_money_stats
>   ADD CONSTRAINT cohort_money_stats_source_domain_check
>   CHECK (source IN ('ipeds_ic_ay', 'scorecard'));
> ```
>
> with `enum class MoneySource(val value: String)` beside the other money enums,
> replacing the two bare constants. Deliberately **not** a sixth vocabulary
> table: the five RFC 158 seeded are unicoach _concepts_, where D6's "unicoach
> authors, external maps in" applies; a source is external identity and
> provenance, not a concept. Precedence stays `ORDERED_SOURCES` in code, guarded
> by the existing unmapped-source fatal, rather than becoming operator-editable
> data.

**What it decided about the softness axis.** Three load-bearing rulings, all
against enriching the source:

1. `MoneySource` is a **code list of publisher identities**, explicitly
   "external identity and provenance, **not a concept**". Attributes belong to
   concepts; the source is not one.
2. It is **deliberately not a vocabulary table** — so there is no seeded row to
   hang a `description`/`assurance` column on, unlike `figure_statuses`.
3. Precedence is **code, not data**, "rather than becoming operator-editable
   data".

This is the strongest existing argument **against C3-as-a-publisher-column and
against any operator-editable softness table**, and simultaneously the argument
that makes **C1 and C2 cheap and in-idiom**: RFC 161 itself gave `MoneySource`
per-member Kotlin metadata (`val value: String`), so adding a second constructor
property is the same move again, code-side, no migration.

It says nothing at all about how much weight a source's numbers bear. The
precedence list
`internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)`
(`rfc/161:275`) is the closest the corpus comes to a quality ranking — and RFC
161 and RFC 162 both justify it on **identity of the fact and upstream-ness**,
never on trust:

> Upstream-wins is then the existing `putIfAbsent` on `PriceKey`, unchanged and
> now actually load-bearing: IPEDS writes first, the Scorecard fills only keys
> IPEDS left empty (the 2,300 IC_PY institutions, and every cohort statistic,
> which IC_AY does not carry). Nothing is averaged; each row keeps its own
> `source` and `source_variable`. — `rfc/161:277-282`

> `CanonicalMoneyLoader.ORDERED_SOURCES` is first-write-wins per natural key
> (RFC 158, P8). SFA is prepended, so SFA wins where the fact is the SAME fact.
> Measured identities: `NPT41-44_PUB == NPIS41-44` (198/197/196/187 of 200
> exact); `NPT4x_PRIV == NPT4x`; `PCTPELL == UPGRNTN/SCUGRAD` (199/200 within
> 1e-4). — `rfc/162-ipeds-sfa-aid-statistics.md:123-127`

**Consequence for brief 0008 success criterion 2:** `ORDERED_SOURCES` is _not_ a
softness ordering and must not be re-read as one. It orders
`IPEDS_SFA > IPEDS_IC_AY >
SCORECARD` because IPEDS is upstream of the
Scorecard's re-publication — which is exactly the "IPEDS is also
institution-reported" problem the brief flags. Prior prose therefore already
establishes that the existing source ranking cannot double as an assurance
ranking.

### RFC 170 — "Need and forms" — **D8**

Verbatim, `rfc/170-need-and-forms.md:79-83`:

> **D8. `common_data_set` joins the `MoneySource` domain.** One more publisher
> on the same source axis every canonical row already carries. CDS field ids
> live in `source_variable`, where source-defined codes belong, and never in a
> table name, a column name or a vocabulary slug.

**What it decided:** the CDS — a school's own unaudited filing — is admitted
onto the **identical** source axis as the Scorecard, with **no distinguishing
attribute**. This is the precise moment the conflation the brief complains about
became structural, and RFC 170 states it as a virtue ("One more publisher on the
same source axis"). It does not defer or reject an assurance attribute; it does
not mention the possibility. The resulting CHECK lists four peers flatly
(`rfc/170:174-176`):

> ```sql
> CONSTRAINT source_documents_source_domain_check
>     CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
>                       'common_data_set')),
> ```

### RFC 170 — **D13** (introduces `source_documents`)

Verbatim, `rfc/170-need-and-forms.md:104-115`:

> **D13. Duplicated data is a defect; this slice removes more than it adds.**
> `source_url` / `archive_url` are properties of a DOCUMENT, not of a fact. One
> CDS filing backs every fact we read out of it, and the three landed CDS tables
> already store the same pair for the same filing (376 + 1,032 + 369 rows over
> ~417 documents), so today those copies can disagree and a corrected URL must
> be written in four places. Adding an eleven-fact-per-school aid table would
> create a fourth duplication site. Instead this RFC introduces
> `source_documents` and points all four tables at it. `source_variable` stays a
> column for now (it duplicates a CODE, not an entity, and normalising it means
> rewriting every canonical loader) and moves to a `source_variables` reference
> table in the cleanup slice, named in the report.

**What it decided about the softness axis.** D13 establishes the _principle_ C3
needs — **"properties of a DOCUMENT, not of a fact"**, one row per (college,
source, year) — and builds the table (`rfc/170:163-179`), which carries only
`source`, `academic_year`, `source_url`, `archive_url`. It carries **no quality,
assurance or coherence column**, and D13's own reasoning is a _deduplication_
argument, not a provenance-richness argument. So D13 is neutral-to-favourable
prior art for C3: the entity exists and is already the citation root; the
argument for putting a per-filing attribute there is D13's own sentence, applied
one step further.

D13 also supplies the counter-cost, in its own voice: `source_documents` rows
are written by the loaders from committed seed, so any new NOT NULL column is a
value every filing must be given — which is exactly the C3 cost line in the
brief.

The related **D2** in the same RFC is the sharpest prior warning against
speculative provenance modelling (`rfc/170:21-27` and `:151-153`):

> `aid_policy_facts` (RFC 158 D9) modeled both as one key/value table with a
> `policy` slug and a boolean-or-number column. That was an honest guess made
> before we had seen the data. Now that we have, the guess is wrong in the exact
> way this brief exists to fix: it is a bag of source cells, not a shape.

> Each row carries a description. It carries NO `is_federal` flag: the first
> draft had one, on the claim that the read layer would say "the federal form
> everyone files" from it, and no read layer does. Speculative until a reader
> exists.

**"Speculative until a reader exists"** is the standing bar any 0008 shape must
clear.

---

## C.2 RFCs that introduced each named artefact, and what each said about the hedge

| artefact                                   | introduced in                                                                                                 | what it said about hedging / softness                                                                                                                             |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FigureStatus` / `figure_statuses`         | **RFC 158** (`:177-186`, per brief-0006 **D3**)                                                               | absence only. `reported` is terminal and undifferentiated. No assurance dimension.                                                                                |
| `MoneySource` enum + `source` CHECK domain | **RFC 161 §2b** (`:156-182`)                                                                                  | source is "external identity and provenance, **not a concept**"; deliberately **not** a vocabulary table; precedence stays in code.                               |
| `ORDERED_SOURCES`                          | **RFC 158 P8** (`:105-110`), realised **RFC 161** (`:262-282`), extended **RFC 162** (`:123-127`, `:155-157`) | ordering justified by upstream-ness and measured variable identity, never by trust. "Nothing is averaged; each row keeps its own `source` and `source_variable`." |
| `source_documents`                         | **RFC 170 D13** (`:104-115`, DDL `:163-179`)                                                                  | a document entity for URL dedup; four publishers listed as flat peers; no quality column.                                                                         |
| `CdsCitation`                              | **RFC 148** (`:195-227`)                                                                                      | the one existing _computed_ hedge-ish artefact: `cited_as` is "a **spoken string** the coach can read aloud verbatim, and it is COMPUTED".                        |
| coach prompt **v19**                       | **RFC 166 §10** (`:717-741`)                                                                                  | the hedge is prompt copy, but pinned by test to one Kotlin home (`FigureStatusCopy`).                                                                             |
| coach prompt **v20**                       | **RFC 170** (`:313-315`, `:348`, `:360`)                                                                      | "New system-prompt seed migration: previous body verbatim plus one paragraph." No hedge derivation; one more hand-written paragraph.                              |

### `CdsCitation` (RFC 148) — the seed of C5, and its stated reason

`rfc/148-admissions-intelligence-in-chat.md:195-227`, verbatim excerpts:

> CDS facts do not: each row is a different school's own document, in its own
> cycle, with its own archive copy. So each of `merit_aid`, `admission_factors`
> and `deadlines` carries its own citation object

> `CdsCitation` and the `putCitation` renderer live together in their own file,
> `admissions/CdsCitation.kt` — one home for the type and its wire shape, so the
> factors and deadlines sections do not reach into the merit section's wire
> object to cite themselves.

> `cited_as` is a **spoken string** the coach can read aloud verbatim, and it is
> COMPUTED: `CdsCitation` holds the college name and the `source_year`, and
> renders the sentence from a cycle label (2024 -> "2024-25"). Holding the parts
> rather than the finished string is what makes "never a bare year" a property
> of the type instead of a convention every construction site has to remember.

That last sentence is a **direct, already-committed argument for C5**,
generalised: hold the parts, render the sentence once, so the property belongs
to the type rather than to every call site's memory. RFC 148 also rules that a
citation with no facts under it must not be emitted (`:336-340`) — "A citation
is a claim that the document says something; with no facts beneath it, it says
nothing" — the same class of honesty rule a derived hedge renderer would have to
keep.

### Prompt v19 (RFC 166) — the strongest prior art of all: **the hedge is already derived once for the absence axis**

`rfc/166-cost-answers-from-canonical.md:456-470` —
`### 6. The six statuses, spoken`:

> `FigureStatusCopy` is an exhaustive `when` over `FigureStatus` with no `else`.
> The words are brief 0003's vocabulary, and the OURS/THEIRS split is RFC 149
> D-B reused a fourth time:
>
> | status | whose | statement | | `reported` | — | no statement; the figure is
> shown plainly. | | `imputed_by_publisher` | publisher | "This is the
> publisher's own estimate for this school, not a figure the school reported." |
> | `suppressed_by_publisher` | publisher | "This figure is withheld by the
> publisher for privacy." | | `not_reported_by_institution` | school | "This
> school did not report this figure." | | `not_applicable` | school | "This
> figure does not apply at this school, and the source says so." | |
> `not_collected_by_us` | **ours** | "We have not collected this figure yet." |

and `rfc/166:490-495`:

> **The prompt says what the payload says.** `SystemPromptCatalogTest` walks
> `FigureStatus.entries` and asserts the v19 status paragraph contains
> `FigureStatusCopy.statementOf(status)` verbatim (`:1459-1467`), so the coach's
> instructions and the wire copy cannot drift into two different sentences about
> the same blank.

and `rfc/166:483-489` (the report inherits the same words, authoring none of
them):

> **The Family Cost Report routes the same status.** `CostReportPage.blankFor`
> (`CostReportPage.kt:626-648`) is exhaustive over `FigureStatus` with no
> `else`, and "Not reported by this school" — a statement about a named school,
> printed for a parent — survives for exactly two cases … The page authors none
> of these words; they are `FigureStatusCopy`'s, carried on
> `CollegeCost.statusNoteFor`

**This is decisive for the 0008 spec.** The pattern brief 0008 asks for — one
derived sentence, every surface inheriting it, the prompt pinned to the same
strings by a test that walks the enum — **already exists and already ships**,
for the `FigureStatus` axis. C5 (and the renderer half of C6) is therefore not a
new pattern to invent but an **extension of `FigureStatusCopy` to a second
axis**, with `SystemPromptCatalogTest`'s enum-walk as the ready-made anti-drift
gate. Note the exact hole: `reported` is the one status with **"no statement;
the figure is shown plainly."** That blank cell is where a softness sentence
would go.

Two further RFC 166 rules constrain any 0008 design (`rfc/166:497-511`):

> 1. **A status that is OURS may never produce an `ArrangementGap`.**
>    `ArrangementGap` may only claim what the SCHOOL published …
>    `not_collected_by_us` therefore maps to `NoTotalReason`, never to
>    `NOT_REPORTED`.

> A routing function that returns the same reason from all of its arms is not a
> rule; it is dead code that reads like one, and it leaves a component WE never
> collected being explained to a family as the school not publishing it.

The second quote is a pre-written review finding against a **vacuous** softness
tier: an `assurance` axis whose three values all render the same sentence would
be dead code that reads like a rule.

### Prompt v20 (RFC 170) — the hand-written paragraph the brief objects to

`rfc/170-need-and-forms.md:300-315`:

> `CollegeCostChatTool` renders it as `aid_policy` with a `CdsCitation` section
> and its own `aid_policy_availability` key for a school with no filing. NOT
> `data_availability`: that list speaks the Scorecard's `CostField` vocabulary,
> so a CDS silence reported there is attributed to the wrong publisher — the
> reason merit aid was already kept out of it. No bare CDS field id reaches a
> tool result; `BareSourceCodeGuard`'s allowlist covers the new keys. New
> system-prompt seed migration: previous body verbatim plus one paragraph.

RFC 170's test list pins the phrasing by hand rather than deriving it
(`rfc/170:423-425`):

> - Read: fully-met share only when both counts exist and naming its cohort; an
>   absent form renders "not listed in this school's CDS"; a school with no
>   filing renders the coverage-honest line; no bare field id in any result.

So v20's hedge is: one appended paragraph + two literal strings asserted in
tests. It is the exact "hand-written at each site" cost brief 0008 names — and
RFC 170's own `aid_policy_availability`-vs-`data_availability` argument shows
the failure mode is **source misattribution**, which is the same defect class
the softness axis addresses.

---

## C.3 Adjacent precedent worth citing at the gate

- **A source can contradict itself, and the repo has already ruled on one such
  case by judgement rather than by data.**
  `rfc/148-admissions-intelligence-in-chat.md:288-296`:

  > The flag is what the school reported about running the round; a date under a
  > round it says it does not offer is a contradiction, and rendering it would
  > state a deadline the school never set. The seed says the flag is the
  > trustworthy half: all 10 affected rows are `round = regular` — a round no US
  > college truly fails to offer — so `offered = false` there is a
  > source-parsing artifact rather than a statement, and 8 of the 10 carry only
  > a notification month, not a closing date at all.

  This is the closest existing analogue to Ian's "14 of 249 filings report more
  borrowers than graduates": an internal-coherence contradiction inside CDS
  data, **counted (10 rows), classified by hand ("a source-parsing artifact
  rather than a statement"), and encoded in the TYPE (`DeadlineRound` sealed
  `Offered`/`NotOffered`) rather than stored as a finding.** C4 is the proposal
  to stop doing that by hand. No RFC argues against storing coherence findings;
  none proposes it either.

- **Derived figures are never stored.** `rfc/170:68-72` (D6):

  > **D6. `meets_full_need` is not stored as a boolean.** No source publishes
  > it. We answer "do they meet full need?" from the average share of need met
  > and the fully-met headcount over its denominator, both cited, both naming
  > their cohort. A stored boolean would be a derived figure, which the schema
  > conventions forbid.

  Binding on 0008: a **coherence finding is a measurement, not a derived
  figure** (it needs two stored numbers and a rule, and its value is that it is
  computed once at ingest) — but C4 must argue that explicitly, because D6 is
  the ready-made objection.

- **Never manufacture a value the source does not contain.** `rfc/170:60-66`
  (D5):

  > **D5. Forms are `required` or `unknown`; never `not required`.** … a `no`
  > does not exist in the source, and manufacturing one is precisely the lie
  > brief 0003's honesty rules forbid.

  Binding on 0008 success criterion 4 ("the store never invents confidence"): D5
  is the same rule one axis over. A three-tier assurance value must be readable
  from the publisher's own documentation, or it is a manufactured `no`.

- **Precedence must never be operator-editable.** `rfc/161:181-182` (quoted
  above). Any 0008 shape that makes softness a seeded, editable table reopens a
  closed decision.

---

## C.4 Which brief candidate shapes are already argued for/against in RFC prose

| shape                                           | prior RFC verdict                                                                        | the argument, quoted                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **C1** `selfReported: Boolean` on `MoneySource` | **mechanically blessed, substantively unaddressed**                                      | RFC 161 §2b already gave `MoneySource` per-member Kotlin metadata — "`enum class MoneySource(val value: String)` beside the other money enums" (`rfc/161:177-178`) — so a second property is in-idiom and needs no migration. But no RFC has ever argued the boolean is _true_; and RFC 161's own upstream-wins reasoning (IPEDS beats Scorecard because it is upstream, `:277-282`) is the counter-evidence that both are institution-reported.                                                                                                                |
| **C2** three-tier `assurance` axis, code-side   | **partly argued FOR by the owned-enumeration rule; guarded by an anti-vacuity rule**     | For: "The repo's own rule applies: an owned enumeration is `TEXT` + `CHECK IN (...)` plus exactly one Kotlin `enum class` with a `fromValue` companion" (`rfc/161:161-164`) — and RFC 161 explicitly kept source metadata **in code**, not in a vocabulary table (`:179-182`). Against a hollow version: "A routing function that returns the same reason from all of its arms is not a rule; it is dead code that reads like one" (`rfc/166:507-509`), and "Speculative until a reader exists" (`rfc/170:153`).                                                |
| **C3** assurance on the DOCUMENT                | **its founding principle is already committed; its cost is stated in the same decision** | For: "`source_url` / `archive_url` are properties of a DOCUMENT, not of a fact. One CDS filing backs every fact we read out of it" (`rfc/170:104-107`) — `source_documents` is already the citation root and already carries `(college, source, academic_year)`. Against: D13's motive was _removing_ duplication ("this slice removes more than it adds", `:104`), and every filing must then be given a value. No RFC argues against a document-level attribute.                                                                                              |
| **C4** stored coherence findings                | **unproposed; one live precedent handled the same problem by hand**                      | RFC 148 counted a CDS contradiction and ruled on it in prose — "the flag is the trustworthy half: all 10 affected rows are `round = regular` … `offered = false` there is a source-parsing artifact rather than a statement" (`rfc/148:293-296`). RFC 170 D7 shows the store already distinguishes OUR gap from the school's silence: "**D7. A corpus `parse_error` is OUR gap.** Those cells land with status `not_collected_by_us` … No row keeps its RFC 158 meaning" (`rfc/170:73-78`). Obstacle to argue past: RFC 170 D6's derived-figure ban (`:68-72`). |
| **C5** derived hedge renderer only              | **already argued FOR, twice, and half-built**                                            | RFC 148 on `CdsCitation`: "Holding the parts rather than the finished string is what makes 'never a bare year' a property of the type instead of a convention every construction site has to remember" (`rfc/148:213-216`). RFC 166 on `FigureStatusCopy`: "The page authors none of these words" (`:488`) and "the coach's instructions and the wire copy cannot drift into two different sentences about the same blank" (`:493-495`). The pattern, the enum-walk prompt test and the exhaustive-`when` discipline are all landed.                            |
| **C6** hybrid                                   | **no prose for or against; each of its three parts is separately covered above**         | Its C2 leg must clear "Speculative until a reader exists" (`rfc/170:153`) — which C5 satisfies by _being_ the reader in the same bet.                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **C0** do nothing                               | **argued against implicitly by RFC 166's whole §6**                                      | RFC 166 already refused to leave the _absence_ hedge in per-site copy, on the grounds that two sites would drift ("cannot drift into two different sentences about the same blank", `:494-495`). RFC 170 then re-introduced hand-written copy for the CDS ("previous body verbatim plus one paragraph", `:313-315`), which is the regression the brief observed.                                                                                                                                                                                                |

**Bottom line for the gate.** No prior RFC decided this axis, deferred it, or
rejected it — the words for it do not occur in 172 RFC files. What the corpus
_does_ supply is (a) a landed, tested, multi-surface derived-copy mechanism for
the sibling axis (`FigureStatusCopy` + `SystemPromptCatalogTest`'s enum walk,
RFC 166 §6) whose `reported` row is literally blank and waiting; (b) a standing
refusal to make source metadata a seeded/editable table (RFC 161 §2b); (c) a
standing bar against speculative columns — "Speculative until a reader exists"
(RFC 170 D13's `aid_forms.is_federal`); and (d) a standing ban on manufacturing
a value the source never published (RFC 170 D5).
