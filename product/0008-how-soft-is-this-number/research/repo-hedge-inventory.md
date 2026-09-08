# Repo hedge inventory — where softness is hand-written today, and what the store could carry

Research for product brief 0008 ("How soft is this number?"). Read-only.

## Method

- Static read of the live tree at `/Users/ian/Work/unicoach` at 2026-09-05.
  `.claude/worktrees/**` and `**/build/**` were excluded everywhere — they are
  stale copies and inflate every grep.
- No gradle, no tests, no DB. Nothing was run that needed the Nix dev shell, so
  no toolchain claim here is second-hand. **Consequence: every test listed in §4
  is identified by reading its source, not by watching it fail.**
- Work was split across three read-only passes whose full notes are kept beside
  this file and are the citation of record:
  - [`_parts/A-prompt-and-store.md`](_parts/A-prompt-and-store.md) — prompt v20,
    schema 0083–0088, the enums, `ORDERED_SOURCES`.
  - [`_parts/B-surfaces-and-seams.md`](_parts/B-surfaces-and-seams.md) —
    service, public-web, iOS, seams, pinned-word tests.
  - [`_parts/C-rfc-history.md`](_parts/C-rfc-history.md) — 172 RFC files.
- Counts below say how they were counted. Where a count is judgment-sensitive it
  says so and gives the band.

---

## 0. The three findings that decide the brief

1. **`MoneySource` never reaches a renderer.** 25 Kotlin files mention it — 5 in
   `db/`, 7 in `college/`, 13 tests — and **zero** in `service/src/main/**` or
   `public-web/src/main/**`. The read row carries it
   (`db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyReadRows.kt:28,48`)
   and the service layer drops it. So no surface can branch on the publisher
   today even if it wanted to. Any of C1/C2/C3 is, at the code level, mostly
   "thread `source` from `CanonicalMoneyReadRows` through `CollegeFigures` into
   `FigureStatusNote`".
2. **The hole has an address.**
   `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/FigureStatusCopy.kt:50`
   maps `FigureStatus.REPORTED -> null`, with the comment "A plainly reported
   figure is shown plainly and needs no sentence beside it" (`:44-45`). A CDS
   self-report and a Scorecard NSLDS figure both land on that arm and both get
   **no sentence at all**. That single `null` is Ian's question, located.
3. **The attribution the product already ships is a half-lie, today, before any
   fifth source.** `CostSources.SCORECARD_ATTRIBUTION`
   (`SingleSchoolBasis.kt:225`) and prompt v20's "Always attribute cost figures
   to the U.S. Department of Education College Scorecard"
   (`db/schema/0088.seed-coach-system-prompt-v20.sql:107-110`) both claim one
   publisher, while `CanonicalMoneyLoader.ORDERED_SOURCES`
   (`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1117`)
   puts `IPEDS_SFA` and `IPEDS_IC_AY` ahead of the Scorecard. IPEDS numbers are
   already being attributed to the wrong publisher in chat and on the report
   page. This is a live defect, not a hypothetical cost of a fifth source.

---

## 1. Every hand-written hedge site

### 1.1 Coach system prompt v20 — 39 hedge sites, 5 of them publisher-named

`db/schema/0088.seed-coach-system-prompt-v20.sql`, body at `:38-405`.

Counting rule (from Part A, so the number is falsifiable): one _hedge site_ is
one contiguous sentence or clause-group that (P) names a publisher or instructs
attribution, (O) assigns ownership of a number or a silence, (A) instructs how
to speak an absence/estimate/imputation, or (I) forbids inventing a value. Found
by reading all 369 body lines, then re-checking with `grep -n` on `report`,
`publish`, `estimat`, `never`, `plainly`, `our`, `source`, `attribute`, `year`.

**39 sites, ±3** (the band is whether adjacent sentences inside one paragraph
split or merge). The full quoted table of all 39 is Part A §A1.2. The subset
that matters is not judgment-sensitive:

**Exactly 5 sites hard-code a publisher name**
(`grep -n
"Scorecard\|Common Data\|Department of Education\|Federal Student Aid"`,
restricted to body lines):

| Site | Line    | Verbatim                                                                                                                                                                   |
| ---- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| H5   | 107-110 | "Always attribute cost figures to the U.S. Department of Education College Scorecard, and when a school doesn't report a figure, say that plainly rather than estimating." |
| H7   | 132-134 | "the figures come from each school's own Common Data Set, so attribute them to it and never to memory."                                                                    |
| H31  | 314-318 | "the school reports them for the families of its own state, and the U.S. Department of Education publishes them on that basis."                                            |
| H33  | 336-340 | "with the award year and the Federal Student Aid source each figure comes from... attribute it to the source the tool cites."                                              |
| H36  | 382-387 | "Every figure there comes from that school's own Common Data Set for the year its citation names, so give the year and the source with the figure."                        |

Note the asymmetry at the centre of this brief: **`MoneySource` has four
members; the prompt names three publishers and never names IPEDS at all.**

The single most load-bearing passage is H35 (`:366-381`), the figure-status
paragraph, which is the _only_ fully data-driven hedge in the prompt — "every
entry carries the sentence to say" — and whose sentences ship from
`FigureStatusCopy.kt:47-72`. Its closing rule is the conflation itself:

> "A figure that is simply reported needs none of these sentences: give the
> number plainly." (`:378-381`)

**What a 5th publisher breaks in the prompt:**

- **Breaks outright (says something false): 5 sites** — the table above. H5's
  "Always" actively forbids the correct behaviour.
- **Silently loses the distinction: 3 sites** — H35 (the fifth publisher's
  figures inherit `reported` and are spoken plainly, with no sentence), H12/H13
  (`:156-170`, the school-publishes / school-estimates / we-assume split is
  written as if living costs had one publisher).
- **Unaffected: the remaining 31 sites** — they key on tool payload shape
  (`data_availability`, `axes_scored`, `comparison_basis`,
  `aid_policy_availability`) or on general rules ("a silence is never a no"), so
  they are already publisher-agnostic.

So **~79% of the prompt's hedge corpus is already derived from payload
structure.** What is not derived is exactly the publisher-identity layer and the
assurance layer — and the assurance layer does not exist as data at all.

**v19 → v20 diff, verified not assumed.** Both SQL bodies were extracted
programmatically and compared: v19 = 20,745 chars, v20 = 22,153 chars,
`v20.startswith(v19)` is **True**. v20 is v19 plus a 1,408-char appended
paragraph, zero interior edits — which confirms the migration's own claim at
`0088:24-30`. **v20 added 4 hedge sentences (H36–H39) and 3 of the 4 restate a
pattern already present elsewhere in the same prompt** (the "silence is never a
no" rule already at H21/H26; the "our gap, not the school's" rule already at
H35). A 75% duplication rate inside one appended paragraph is the strongest
single piece of evidence for the brief's thesis.

**Cost of changing any of this:** the seeded row is immutable (`0088:32-33` —
"the v19 row is immutable and stays in the catalog"). One reworded hedge = a new
numbered migration + a new version string + a `service.conf` flip.

### 1.2 `service/src/main/kotlin/ed/unicoach/coaching/**` — 19 hedge strings + 1 attribution constant

Full table with verbatim strings in Part B §1. The load-bearing ones:

| Site                                                                                | file:line                                                                                                                                                         | What it says                                                                                                                                                         | 5th-source damage                                                                                                                                                                                                                                      |
| ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `CostSources.SCORECARD_ATTRIBUTION`                                                 | `costs/SingleSchoolBasis.kt:225`                                                                                                                                  | `"U.S. Department of Education College Scorecard"` — re-exported as `CollegeCostChatTool.SOURCE_ATTRIBUTION` (`:1125`) and emitted as the payload's one `source` key | **Sharpest break in the tree**, and already half-wrong (§0.3). Per-_payload_, not per-figure.                                                                                                                                                          |
| `CdsCitation.citedAs`                                                               | `admissions/CdsCitation.kt:35`                                                                                                                                    | `"$collegeName's ${cycleLabel(sourceYear)} Common Data Set"`                                                                                                         | The publisher name is string-concatenated **into the type**, and `cycleLabel` (`:46`) hard-codes that publisher's year convention. A 5th source needs a second `XxxCitation` + `putXxxCitation`, or `CdsCitation` generalised to take a `MoneySource`. |
| `AidPolicyWire.FORMS_NOTE` / `FORMS_NOT_COLLECTED_NOTE`                             | `costs/AidPolicyWire.kt:156-159`, `:144-147`                                                                                                                      | two paragraphs, each opening "This school's Common Data Set…"                                                                                                        | hard-codes the publisher                                                                                                                                                                                                                               |
| `AID_POLICY_NO_FILING` / `AID_POLICY_NO_FACT_IN_FILING`                             | `costs/CollegeCostChatTool.kt:1278-1281`, `:1289-1292`                                                                                                            | "We hold no Common Data Set filing…" / "We hold this school's Common Data Set filing, but it reports none of the need figures…"                                      | hard-codes the publisher. **These two are the RFC 170 failure mode the brief cites** — the pair a reader must choose between correctly.                                                                                                                |
| `FigureStatusCopy` 5 statements + year-gap builder                                  | `costs/canonical/FigureStatusCopy.kt:50-72,148-149`                                                                                                               | the six status sentences                                                                                                                                             | **Unaffected.** Every one says "the publisher"/"the source" — agentless on purpose. This is the one file already shaped to take an assurance axis without per-publisher copy.                                                                          |
| `WithheldReason`, `ArrangementGap`, `NoTotalReason`, `SingleSchoolBasis` statements | `costs/BlendedFigureBasis.kt:52-55,75`; `costs/ComparisonBasis.kt:137-139,639,656`; `costs/CostBreakdown.kt:655,666,677,695`; `costs/SingleSchoolBasis.kt:74-178` | hedge English on enum members / basis statements                                                                                                                     | mostly unaffected — about year, cohort and arrangement, not publisher                                                                                                                                                                                  |

**Count: 19 distinct hand-written hedge strings** (5 status statements + 1
year-gap builder + 4 `NoTotalReason` + 2 `ArrangementGap` + 2 `WithheldReason` +
4 CDS-naming constants + 1 at-home assumption), plus 1 attribution constant and
~7 basis statements about year/cohort. **Four of them hard-code "Common Data
Set". None of them is derived from `MoneySource`, because `MoneySource` is not
in scope in this module.**

**There is no `sourceName(MoneySource): String` anywhere in the tree.** The
nearest thing is `MoneySource.value`
(`db/src/main/kotlin/ed/unicoach/db/models/MoneySource.kt:27,30,33,42`), which
is a wire slug (`"common_data_set"`), not a spoken label. **No spoken publisher
label exists as data.**

### 1.3 `public-web/.../render/CostReportPage.kt` — 16 hedge constants

Full table in Part B §2. The decisive one:

```kotlin
// CostReportPage.kt:963-984
private fun FlowContent.sourcesSection() { ... 
  p { +("The cost and price figures come from the ${CostSources.SCORECARD_ATTRIBUTION}. The merit figures " +
        "come from each school's own Common Data Set, cited beside them.") }
```

`sourcesSection()` **takes no arguments**, reads no profile, and is called
unconditionally (`:259`). It asserts a two-publisher world in a literal. It is
already wrong for IPEDS-sourced prices, and nothing in the type system would
notice if a fifth publisher were added and nobody edited it. This is brief
criterion 1 failing, in one function.

The counter-example, in the same file, is C5 working in miniature:
`withheldBlankFor` (`:120-123`) supplies only the frame and imports the reason's
_words_ from the domain (`WithheldReason.cellPhrase`). Also
`CostReportPage.kt:932` — `+"Source: ${merit.source.citedAs}."` — delegates the
citation to the domain type.

### 1.4 iOS — **no hedge copy at all. The brief is wrong here.**

The app is at `ios-app/` (not `ios/` or `app/`).
`find ios-app -name '*.swift'
-not -path '*/build/*'` → **124** Swift files.
`grep -riE "scorecard|common data set|ipeds|self-report|provenance|unaudited|department of education"`
over them → **4 hits, all doc comments**, and the two `provenance` ones mean
_conversational_ provenance (RFC 91/136 — which chat turn added a college), not
data provenance: `ResidencyStates.swift:12,47`,
`CollegeEntryDetailView.swift:92`, `CollegeListModels.swift:27`.
`ios-app/UnicoachiOSTests/` (40 files) contains **zero** hedge assertions.

Why: `ConversationView.swift:296,302` reads the coach's reply as an opaque
string and hands it to `MarkdownView(source:)`. **iOS is a pass-through.** The
brief's sentence "the iOS surfaces write theirs" is not true of the live tree.
This is good news for the bet: iOS inherits any derived hedge with zero Swift
change.

---

## 2. What the store already models

Exact column lists for every table in `0083`–`0088` are in Part A §A2. Summary
of what bears on softness:

**`money_source` DOMAIN** —
`db/schema/0087.create-aid-form-requirements.sql:119-122`:

```sql
CREATE DOMAIN money_source AS TEXT
    CONSTRAINT money_source_check
        CHECK (VALUE IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard', 'common_data_set'));
```

Its comment (`:118`): "**A fifth publisher is now one ALTER DOMAIN.**" The
migration is genuinely cheap; the _copy_ is not, which is the whole brief.

**`MoneySource.kt:23-50`** — four members, and **exactly one property,
`val value: String`** (`:24`). ⚠️ **Correction to the brief:** brief §C1 says
"`MoneySource` already carries per-member Kotlin metadata." That is false —
verified by `grep -n "val "` on the file (two hits, the second is the private
`BY_VALUE` map at `:46`). The enum with per-member metadata is `FigureStatus`
(`valueBearing: Boolean`). C1/C2 _introduce_ the first `MoneySource` attribute.
They are still cheap; the claim is just smaller than the brief makes it.

**`FigureStatus.kt:12-38`** — six members, two properties (`value`,
`valueBearing`). `REPORTED`'s doc comment is the conflation, in one line: "The
institution reported it and the source published it." The seeded row description
repeats it verbatim (`db/data/money-vocabulary.json:54`).

**`figure_statuses`** (`0083:80-88`) — `slug`, `description`, `value_bearing`,
`created_at`, `updated_at`. **Four columns of substance; there is no fifth, and
no spare.**

**`source_documents`** (`0087:139-157`) — `id`, `college_id`, `source`,
`academic_year`, `source_url NOT NULL`, `archive_url NULL`, `created_at`,
`updated_at`. Natural key `(college_id, source, academic_year)`. **Seven
columns. No quality, assurance, checked-by, retrieved-at or coherence column
exists.**

**`price_figures`** (`0083:184-217`) — `id`, `college_id`, `price_concept`,
`residency_basis`, `arrangement`, `academic_year`, `amount_usd NULL`, `status`,
`source`, `source_variable`, **`publisher_flag TEXT NULL`**, `created_at`,
`updated_at`.

**`cohort_money_stats`** (`0083:247-285`, widened by `0087`) — `id`,
`college_id`, `measure`, `population`, `residency_scope`, `aid_scope`,
`income_band NULL`, `vintage NULL`, `value NUMERIC NULL`, `status`, `source`,
`source_variable`, **`publisher_flag TEXT NULL`**,
**`source_document_id UUID
NULL`**, `created_at`, `updated_at`.

`publisher_flag` is the closest thing in the store to a per-row softness
attribute: it holds the raw IPEDS X-code (R reported, P/N imputed, Z implied
zero). But it is source-defined and raw by design (`0083:237-240`,
`0085:98-102`), and it is **NULL for the Scorecard and for the CDS** — so it is
not a universal axis.

**`ORDERED_SOURCES`** — `CanonicalMoneyLoader.kt:1117`:

```kotlin
internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_SFA, MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)
```

with `SOURCES_FILLED_ELSEWHERE = setOf(MoneySource.COMMON_DATA_SET)` (`:1127`)
and an `init` block (`:1129-1143`) that fatals on any unranked member. Its doc
comment ranks on **closeness to the publisher of record** — "the publisher's
number displaces the copy rather than being averaged with it" (`:1100-1102`) —
which is a _provenance_ argument, not an _assurance_ one. Nothing there says
audited or self-reported, and the CDS is excluded from ranking altogether. **An
assurance tier would be a new, orthogonal axis, not a reinterpretation of
`ORDERED_SOURCES`.**

**Assurance room today: zero.**
`grep -rn -i
"self_report\|selfReported\|assurance\|unaudited\|attestation"`
over `db college service rest-server queue-worker ios-app` → no relevant hits (3
noise: two CIP program names in `db/data/codebooks.json`, one unrelated
comment). Ranked homes, cheapest first: a `MoneySource` constructor property
(**no migration** — the DOMAIN stores only the slug); a nullable column on
`source_documents` (**one ALTER TABLE**, and RFC 170 already set the
per-source-mandatory pattern C3 would reuse —
`cohort_money_stats_cds_cites_document_check`, `0087:418-419`); a new coherence
table for C4. `figure_statuses` is the wrong axis and the most expensive: a
seventh status is enumerated literally inside **four** `value_iff_status_check`
CHECKs (`0083:206-208`, `0083:279-281`, `0085:150-152`, `0087:265-267`).

---

## 3. Where a derived hedge would be computed and rendered — the seam per surface

| Surface               | Seam                                                                                                  | file:line                                                                           | Honest verdict                                                                                                                                                                                                                                                                                                                                                                                                                    |
| --------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **chat tools / wire** | `FigureStatusCopy.statementOf(status): String?`                                                       | `service/.../costs/canonical/FigureStatusCopy.kt:47`                                | **Yes — this is the seam.** One function, store code → spoken sentence; already publisher-neutral prose; every `when` exhaustive with **no `else`** by stated design (`:36-39`) so a widened signature forces every arm to be re-decided at compile time. Output funnels through one wire renderer, `CollegeCostChatTool.figureStatusObject` (`:651`).                                                                            |
| **cost report page**  | `blankFor(cost, field, otherwise)` for absences; `figureOf(amountUsd, blank, note)` for shown figures | `public-web/.../CostReportPage.kt:645-666`; `:770-774`                              | **Yes.** `blankFor` reads `note.statement` — the _same_ `FigureStatusCopy` output the chat tool uses, so widening the seam serves both. For a hedge on a figure that IS shown, `SchoolFigure.Amount.note: String?` (`:760`, rendered at `:901` as `span("report-note")`) is an existing hook currently unused for provenance. **No seam for `sourcesSection()` (`:963`)** — it takes no arguments and can only be edited by hand. |
| **coach prompt**      | **No code seam exists.**                                                                              | `db/schema/0088...sql`; version pinned in `service/src/main/resources/service.conf` | Honest answer: the prompt is an immutable DB row; a reworded hedge is a v21 migration. The nearest _code_ seam is `CollegeCostChatTool.DESCRIPTION` (`:1447`), which reaches the model in the same context window and is already assembled from Kotlin constants (`FigureStatus.*.value`, `AT_HOME_ASSUMPTION_STATEMENT` at `:1549`). A derived hedge can be interpolated there with no migration.                                |
| **iOS**               | None needed, none exists.                                                                             | `ios-app/UnicoachiOS/ConversationView.swift:296,302`                                | Pass-through markdown. Inherits the hedge free. A future native cost screen would need its own seam; today there is none (no Swift file reads `figure_statuses`, `data_availability` or `withheld_figures` — grepped, zero hits).                                                                                                                                                                                                 |

**The seam picture in one sentence:** three of four surfaces already funnel
through `FigureStatusCopy` or read its output, so the _renderer_ half of C5 is
one function signature. What is missing is not a seam — it is the **input**:
`MoneySource` does not reach `service/src/main/kotlin` at all.

---

## 4. Which candidate shapes the code supports cheaply

| Shape                                           | Code support                                                                                                                                                                                                                                                                                                                                                                                                   | Evidence                                         |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| **C1** `selfReported: Boolean` on `MoneySource` | **Mechanically cheapest — no migration.** But it is the _first_ per-member attribute, not a reuse (§2 correction), and the code's own precedence reasoning is evidence against the boolean being true: IPEDS beats the Scorecard because it is _upstream_, and both are institution-reported (`CanonicalMoneyLoader.kt:1100-1115`).                                                                            | `MoneySource.kt:23-25`; `0087:119-122`           |
| **C2** three-tier `assurance` on the source     | **Cheap and in-idiom.** `MoneySource.kt:14-18` argues in its own words that the enum is the home for "external identity and **provenance**", and `:20-21` keeps precedence out of it, so a second orthogonal property fits. Guard: must not be hollow — `rfc/166:507-509`, "a routing function that returns the same reason from all of its arms is not a rule; it is dead code".                              | `MoneySource.kt:14-21`                           |
| **C3** assurance on `source_documents`          | **One nullable `ALTER TABLE`**, and the per-source-mandatory CHECK pattern already exists (`0087:418-419`). But `source_documents` is only populated for the CDS today (`cohort_money_stats_cds_cites_document_check`), so a document-level tier says nothing about Scorecard or IPEDS figures without a much larger backfill.                                                                                 | `0087:139-157`, `:414-424`                       |
| **C4** stored coherence findings                | **New table; no foundation exists.** `college_index_build.canonical_money_summary JSONB NULL` (`0083:370`) is the only free-form metadata column in the money layer and is the right grain for _counts_ of findings, wrong grain for per-figure findings. Must argue past RFC 170 D6's derived-figure ban.                                                                                                     | `0083:367-386`                                   |
| **C5** derived hedge renderer                   | **Cheapest with real value, and half-built.** `FigureStatusCopy` already derives five sentences plus a `FigureGapOwner {SCHOOL, PUBLISHER, UNICOACH}` axis (`:16-25`) with a no-`else` exhaustiveness discipline. `withheldBlankFor` (`CostReportPage.kt:120-123`) already imports domain words. The hole is narrow: **nothing derives from `MoneySource`, and `MoneySource` carries nothing to derive from.** | `FigureStatusCopy.kt:16-25,36-39,47-72`          |
| **C6** hybrid                                   | Supported as the sum of the above. Its C2 leg satisfies the "speculative until a reader exists" bar (`rfc/170:153`) precisely _because_ C5 is the reader in the same bet.                                                                                                                                                                                                                                      | —                                                |
| **C0** do nothing                               | Leaves the live IPEDS-attributed-to-Scorecard defect (§0.3) in place, and it cannot be fixed in the prompt without a migration anyway.                                                                                                                                                                                                                                                                         | `0088:107-110` vs `CanonicalMoneyLoader.kt:1117` |

### Tests that pin hedge WORDS

**26 assertions across 7 files**, found with a 17-alternative literal regex over
`--include=*.kt --include=*.swift`, filtered to `src/test`/`Tests`, worktrees
and `build/` excluded. **iOS: 0. `db` module: 0** (db tests assert codes, never
copy). Full per-line tables in Part B §5.

| File                                                                                         | Count | What is pinned                                                                                                                                                                                                                                                                                                       |
| -------------------------------------------------------------------------------------------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`                           | 12    | `:176,213,407,431,527` "Not reported by this school"; `:251` pins the **HTML too** (`"$0<span class=\"report-note\">our assumption, not a figure this school published</span>"`); `:322` the `citedAs` shape; `:329,330` the `sourcesSection` publisher names; `:358,375,515,548,563,739`; `:460` a **negative** pin |
| `service/.../costs/CollegeCostServiceTest.kt`                                                | 9     | `:200,208,216,248,304,1221,1335,1352` — `assertEquals` on the **exact** `FigureStatusCopy` sentence. Tightest coupling in the tree. Plus `:2805`                                                                                                                                                                     |
| `service/.../costs/CollegeCostChatToolTest.kt`                                               | 9     | `:574` `assertEquals("U.S. Department of Education College Scorecard", result.getValue("source"))`; `:983,1233` `citedAs`; `:1044,1098` `AidPolicyWire` notes; `:1159,1167` by constant (reword-safe); `:1226` negative pin; `:2298`                                                                                 |
| `service/.../SystemPromptCatalogTest.kt`                                                     | 6+    | `:283,345,462` "U.S. Department of Education College Scorecard"; `:500` "Common Data Set"; `:611,730,828,1402`. Pinned against a **migration**, not a constant — a prompt reword needs a new seed migration _and_ these edits.                                                                                       |
| `service/.../admissions/CollegeAdmissionsServiceTest.kt`, `CollegeAdmissionsChatToolTest.kt` | 8     | `:361,377,379,400` / `:113,511,535,543` — the `"<College>'s <YYYY-YY> Common Data Set"` `citedAs` shape                                                                                                                                                                                                              |
| `service/.../fitlens/FitLensServiceTest.kt`                                                  | 3     | `:1218,1344` exact status sentences inside a rendered blob; `:1319` negative pin                                                                                                                                                                                                                                     |
| `service/.../costs/canonical/CollegeFiguresTest.kt`                                          | 1     | `:642` the imputed-by-publisher sentence                                                                                                                                                                                                                                                                             |

**Blast radius, and the cheapest possible first slice:** the six
`FigureStatusCopy` sentences are pinned by `assertEquals` at 9 sites in 2 files;
the `citedAs` format at 9 sites in 4 files. But **`FigureStatusCopy.kt:50`
(`REPORTED -> null`) has zero test coverage** — no test asserts anything about a
reported figure's sentence. A slice that only _adds_ a source-aware clause on
the `REPORTED` arm breaks **none** of the 26 assertions.

---

## 5. Prior RFC decisions on this axis

**Headline: the axis has never been decided, deferred, or rejected — it has
never been named.** Across 172 `rfc/*.md`: `self-report`, `self_report`,
`unaudited`, `softness`, `hedge`, `voluntar*`, `keyholder`, `edit check`,
`administrative
record` → **0 hits each**. `assurance` → 1 (`rfc/01:62`,
"Quality Assurance & Testing"). `audited` → 3 (all about code review). `attest`
→ 3 (App Attest, snapshots). `provenance` appears in 28 files and **always**
means ingest provenance (sha / url / date). Full method and quotes in Part C.

| RFC                                                                                                            | What it decided, and what it means here                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **RFC 158 "D3"** (a brief-0006 gate-1 decision, cited at `rfc/158:95,184,238`; RFC 158's own units are P1–P12) | Models **absence only**. `reported` is terminal and undifferentiated.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **RFC 161 §2b "The money source becomes an owned enumeration (decision 6)"** (`rfc/161:156-182`)               | A source is "**external identity and provenance, not a concept**"; deliberately **not** a vocabulary table; precedence stays in code "rather than becoming operator-editable data" (`:181-182`). **Blesses C1/C2 mechanically. Blocks any seeded or operator-editable softness table.**                                                                                                                                                                                                                                                                                                      |
| **RFC 170 D8** (`rfc/170:79-83`)                                                                               | Put `common_data_set` on the **same flat source axis** as the Scorecard with no distinguishing attribute. This is the exact moment the conflation became structural.                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **RFC 170 D13** (`rfc/170:104-115`)                                                                            | Gives C3 its founding principle verbatim: "`source_url` / `archive_url` are properties of a **DOCUMENT**, not of a fact. One CDS filing backs every fact we read out of it." Also the standing bar: **"Speculative until a reader exists"** (`:153`), which killed `aid_forms.is_federal`.                                                                                                                                                                                                                                                                                                   |
| **RFC 166 §6** (`rfc/166:456-495`) — **the biggest find**                                                      | **C5 is already landed for the sibling axis.** `FigureStatusCopy` + `SystemPromptCatalogTest`'s enum walk + "The page authors none of these words" (`:488`) + "the coach's instructions and the wire copy cannot drift into two different sentences about the same blank" (`:493-495`). Its `reported` row is literally "no statement; the figure is shown plainly" — **the blank cell where a softness sentence goes.** Also the anti-vacuity rule: "a routing function that returns the same reason from all of its arms is not a rule; it is dead code that reads like one" (`:507-509`). |
| **RFC 148** (`rfc/148:213-216`, `:288-296`)                                                                    | Introduced `CdsCitation` — "Holding the parts rather than the finished string is what makes 'never a bare year' a property of the type". And the **C4 precedent**: a CDS self-contradiction counted (10 rows) and ruled on **by hand** — "the flag is the trustworthy half … `offered = false` there is a source-parsing artifact rather than a statement". C4 is the proposal to stop doing that by hand.                                                                                                                                                                                   |
| **RFC 170 D5** (`rfc/170:60-66`)                                                                               | "Forms are `required` or `unknown`; never `not required` … manufacturing one is precisely the lie brief 0003's honesty rules forbid." Binding on success criterion 4: **an assurance tier must be readable from the publisher's own documentation, or it is a manufactured `no`.**                                                                                                                                                                                                                                                                                                           |
| **RFC 170 D6** (`rfc/170:68-72`)                                                                               | "A stored boolean would be a derived figure, which the schema conventions forbid." **The ready-made objection to C4** — a coherence-finding slice must argue that a measurement computed once at ingest from two stored numbers is not a derived figure.                                                                                                                                                                                                                                                                                                                                     |

**Three standing bars any shape must clear:** (1)
speculative-until-a-reader-exists (`170:153`); (2) anti-vacuity (`166:507-509`);
(3) never manufacture a value the source never published (`170:60-66`). Plus one
prohibition: softness must **not** become a seeded, operator-editable table
(`161:181-182`).

---

## Recommendation

**C6, sequenced so that C5 lands first and alone.**

The first slice should be a pure C5 move with no new data at all: thread
`MoneySource` from `CanonicalMoneyReadRows` into `FigureStatusNote`, widen
`FigureStatusCopy.statementOf` to take the source, and replace the four
hard-coded "Common Data Set" constants and `CostSources.SCORECARD_ATTRIBUTION`
with a derived per-figure attribution. That single slice:

- fixes a **live** defect (IPEDS figures attributed to the Scorecard, in chat
  and on the parent-facing report page);
- breaks **zero** of the 26 pinned-word tests if the `REPORTED` arm is only
  _added to_;
- needs **no migration** and **no prompt v21**;
- gives C2 a reader, which is what clears RFC 170's "speculative until a reader
  exists" bar.

Only then add C2 (a three-tier `assurance` property on `MoneySource` — no
migration, guarded against vacuity by RFC 166's rule). C3 should be
**deferred**: `source_documents` is populated for the CDS alone today, so a
document-level tier would say nothing about the majority of figures. C4 is a
separate bet with its own argument to win against RFC 170 D6, and should not be
bundled into this one.

Two corrections the brief should absorb before gate 1: **iOS writes no hedge
copy at all** (§1.4), and **`MoneySource` carries no per-member metadata today**
(§2).
