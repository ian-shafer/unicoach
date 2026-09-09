# RFC 179 — Assurance tiers: how hard the number behind a figure is

Slice: `soft/02/assurance-tiers` (brief 0008, gate 2 approved 2026-09-05). Lane
A, behind RFC 177.

## Summary

unicoach speaks four publishers with one voice. RFC 177 taught the copy seam WHO
published a figure; it did not teach it what kind of number a publisher
produces. "The college had to file this with the federal government and the form
edit-checks it" and "the college typed this about itself and nobody checks it"
are different claims about a family's money, and today both are said the same
way.

This RFC adds the second half of the key. `AssuranceTier` is a three-member
Kotlin enum — `ADMINISTRATIVE_RECORD`, `MANDATORY_SURVEY`,
`VOLUNTARY_SELF_REPORT` (brief D3) — resolved by a **pure function of
`(MoneySource, source_variable)`**, the pair both fact tables already store
(`price_figures.source_variable`,
`db/schema/0083.create-canonical-money-tables.sql:194`;
`cohort_money_stats.source_variable`, `0083:259`). **No migration, no column, no
seeded table, and nothing on `MoneySource`** (brief D2). The tier is orthogonal
to `FigureStatus` and is read as a PAIR with it: an imputed IPEDS cell is soft
in a different way from a Common Data Set cell, and the two may not collapse.

Three things have to be built for that sentence to be true rather than
decorative.

1. **`source_variable` has to travel.** It reaches the service layer on both
   read rows (`CanonicalMoneyReadRows.kt:29,49`) and dies at exactly two lines,
   `CollegeFigures.kt:410` and `:472`. It is threaded one field further through
   `DatedFigure`, `DatedStat`, `FigureProvenance` and `FigureStatusNote`,
   exactly as RFC 177 threaded `source`.
2. **The tier needs somewhere to be seen.** `CollegeCostService.kt:1937` drops
   the `FigureStatusNote` for every shown, non-imputed figure, so the seam today
   speaks only for blank and imputed cells. A tier hung only there would never
   appear beside a dollar amount. A shown figure now gets a note too, carrying
   its source, its `source_variable` and its tier, with the STATUS statement
   null for a shown `REPORTED` figure — no new status prose, one new sentence.
3. **The softest tier's one visible figure is served by another door.** "Average
   percent of need met" is CDS field `H.209` and reaches a family only through
   `AidPolicyDao` (`AidPolicyDao.kt:50-146`), which never selects
   `source_variable` and binds the source as a WHERE constant. That read gains
   the column and derives the tier through the SAME resolver.

`source_variable` is an OPEN `TEXT` column — there is no domain CHECK on any of
the four tables — so no `when` over it can be exhaustive and the compiler cannot
supply RFC 177's safety net. The resolver therefore refuses an unmapped variable
with a runtime FATAL, on the `ORDERED_SOURCES` unranked-member precedent
(`CanonicalMoneyLoader.kt:1144-1153`), and never silently defaults to the
softest or the hardest tier. Three of the four key sets are closed at compile
time; this RFC closes the fourth at ingest.

No DDL. No new prompt version. One new committed data file: the Scorecard
variable→`SOURCE` transcription the falsifiable pin needs.

## Decisions

**D1. The enum and its resolver live in `:db` models; the English lives in
`:service`.** `AssuranceTier` is a derived reading of two stored codes, the same
layer as `MoneySource`, `FigureStatus` and `IpedsImputationFlag`, and it carries
no English at all. Its home is
`db/src/main/kotlin/ed/unicoach/db/models/AssuranceTier.kt`. The three sentences
a family hears are a rendering decision and live beside the other one, in a new
`AssuranceTierCopy` in
`service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/` — RFC 177 D1's
rule, unchanged.

The split is not tidiness. It is what lets the ONE resolver serve both readers:
`AidPolicyDao` is in `:db` and cannot see `:service`, so a service-side resolver
would have forced a second mapping for the Common Data Set — exactly the special
case this slice must not have. It also puts the closure tests in `:college`,
where the loader vocabularies (`SfaVariables`, `IpedsChargeVocabulary`,
`ScorecardInstitutionColumns`) are already visible, so the walks derive their
key space instead of retyping it. `SCORECARD_TIERS` is therefore **`val`, not
`internal val`**: Kotlin `internal` is per-Gradle-module and does not cross
`:db` -> `:college`, and the only alternative was moving the closure test into
`:db` and re-typing every Scorecard string there, which is the falsifiability
the walk exists for.

**D2. The tier is a function of the pair, and only one source needs the second
half of it.** `AssuranceTier.of(source, sourceVariable)` is a `when (source)`
with **no `else`**, so a fifth `MoneySource` fails to compile here:

| `MoneySource`     | Arm                                        |
| ----------------- | ------------------------------------------ |
| `IPEDS_SFA`       | `MANDATORY_SURVEY` for every variable      |
| `IPEDS_IC_AY`     | `MANDATORY_SURVEY` for every variable      |
| `COMMON_DATA_SET` | `VOLUNTARY_SELF_REPORT` for every variable |
| `SCORECARD`       | `SCORECARD_TIERS[variable]`, or FATAL      |

The three whole-source arms are a decision, not a default: an IC_AY or SFA cell
is a cell of one compelled, edit-checked survey whichever variable it is, and a
Common Data Set cell is one filing the school published about itself whichever
field id it carries. The instrument is a property of the source there. The
Scorecard is the one publisher for which that is false — it is mostly a relay —
and it is the one arm keyed on the variable.

The unmapped-variable fatal therefore bites where an unmapped variable can
exist, and it is fatal in the house's own shape: this is a read-path
reconstruction failure over a stored pair, so it leaves `:db` as the located
`CorruptPersistedValueException` every sibling decode raises — carrying both
halves of the pair as the exception's `value`, a required `location` from the
caller (the row identity, or the `CostField` being served), and the
`PermanentError` marker a bare `error(...)` would have lost. A Scorecard column
added to the loader without a tier stops the read instead of being served under
a guessed one.

**D3. `SCORECARD_TIERS` has 24 entries, for 18 published cells.** The loader
writes 24 distinct Scorecard `source_variable` strings and they cover 18
conceptual cells: `NPT4` and its five income bands are each published twice,
`_PUB` and `_PRIV`, and the loader picks one per institution from `CONTROL`
(`CanonicalMoneyLoader.kt:1001`, `:887-888`). Both numbers are true and they
count different things; the map is keyed on the literal string, so it needs 24.
Of the 18 cells, 16 are re-published IPEDS and 2 are federal administrative
records — `GRAD_DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10` (Treasury). The spec's
"16 of 18" is right about which columns; said of the strings it would be wrong.

`DEBT_MDN`, which the slice text names beside `GRAD_DEBT_MDN`, is **not** a
column this loader reads (`ScorecardInstitutionColumns.kt:14-31`). It is not in
the map and not in the committed transcription: a tier for a variable no loader
writes is a row with no reader.

**D4. The falsifiable pin needs a committed dictionary, so this RFC commits the
24 rows of it that we use.** The spec asks for a test against "a committed copy
of the College Scorecard data dictionary". There is none: RFC 173 deliberately
declined to pin `CollegeScorecardDataDictionary.xlsx`
(`rfc/173-fetch-scorecard.md:253-256`, "727 KB of unused provenance"), and there
is no xlsx reader on the JVM classpath — `poi`/`xlsx` appear nowhere in
`gradle/libs.versions.toml` or any `build.gradle.kts`.

So this RFC commits a **derived** file, on the `IpedsImputationFlag` precedent
(`IpedsImputationFlag.kt:6-13`: thirteen codes transcribed from the publisher's
own dictionary "rather than fetched, because a thirteen-row table that changes
when NCES changes its survey is authored vocabulary with a citation, not a data
file to parse at runtime"). The file is
`db/seed/scorecard/dictionary-variable-sources.csv`, header
`variable_name,source`, 24 data rows — one per string in `SCORECARD_TIERS`, each
carrying the `SOURCE` value the published dictionary states for it. A test reads
it and asserts our map agrees with it column by column.

Two corrections to how it is pinned, both forced by the tree:

- **The digest goes in `FETCH-NOTES.md`, not `PROVENANCE.json`.**
  `PROVENANCE.json` is written whole by `bin/fetch-scorecard`
  (`bin/fetch-scorecard:1601-1605`) and `db/seed/scorecard/FETCH-NOTES.md:14`
  says of it "Nothing in it was hand edited". A hand-added key would be a lie in
  that sentence and would be erased by the next fetch. `FETCH-NOTES.md` is the
  hand-authored record in the same directory and already the citation home, so
  it gains a short stanza: the dictionary URL
  (`https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx`),
  the sheet (`Institution_Data_Dictionary`), the download date (2026-09-05), the
  bytes (727,278), the documentation PDF and version it is corroborated against
  ("Version: September 2025"), and the sha256 of the committed CSV. The same
  sha256 is typed beside the test as a constant, so an undocumented edit of the
  transcription fails a test rather than moving a tier quietly.
- **`.gitignore` needs a third exception.** `db/seed/scorecard/*` is ignored
  with `!PROVENANCE.json` and `!FETCH-NOTES.md` (`.gitignore:54-56`); the CSV
  needs its own `!` line or it cannot be committed at all.

**D5. The Common Data Set key set is closed at ingest, so it is closed
everywhere.** `CdsSeedLoader.kt:664` reads `source_variable` verbatim out of
`db/seed/cds/aid_policy.csv` through `getString`, which only trims and refuses
empty. There is no allow-list, no CHECK and no cross-check against the `fact`
column — which IS closed (the loader's fact routing table, 22 entries, an
unknown `fact` fatal through `getCode`).

This RFC gives each entry of that table the CDS field id its fact is published
under. The routing table becomes `AID_POLICY_CELLS: Map<String, AidPolicyCell>`,
where `AidPolicyCell` is the PAIR `(fact, fieldId)` — 22 entries, 1:1 with the
committed seed's 22 distinct `(fact, source_variable)` pairs. A row whose
`source_variable` is not the field id its `fact` is published under is refused
with the same `Defect.UnknownCode` an unknown `fact` raises.

**One map to a pair, not two parallel String-keyed maps.** The two facts are one
decision about one cell — "line i is the average share of need met, and the
filing prints it as `H.209`" — and split across two maps either half could go
missing while the other shipped, with only a hand-written class-init equality
check standing between that and a stored cell id no tier answers for. Held as
one value the half-declaration does not compile, and the check has nothing left
to assert.

**The check runs AFTER the `fact` lookup, not inside `aidPolicyRow`.**
`aidPolicyRow` runs before the router's `getCode(record, "fact", ...)`, and
`college/src/test/resources/cds-aid-policy-unknown-fact-fixture.csv` carries
`fact=meets_full_need` with `source_variable=H.999`. Checking the field id first
would make that fixture fail on the wrong column and would move
`CdsSeedLoaderTest.kt:373-384`'s pinned `assertEquals("fact", defect.column)`.
The pair check belongs where the fact is known good, so the unknown-fact defect
keeps its place. It takes the DECODED cell the router already holds, rather than
re-reading and re-decoding the `fact` column itself: a second decode whose
`getValue` is safe only because of the first one is a coupling nothing states.

**D6. A shown figure gets a note.** `CollegeCostService.kt:1937` —
`if (shown && status != FigureStatus.IMPUTED_BY_PUBLISHER) return@mapNotNull null`
— is why RFC 177's `REPORTED` arm is unreachable on both surfaces
(`rfc/177-one-hedge-seam.md:305-308`), and it is the structural reason a tier
would be invisible on every dollar amount a family reads. The drop is removed:
`figureStatusesOf` now emits one note per `CostField` that has provenance, and
`FigureStatusNote.statement` becomes nullable, null exactly for a shown
`REPORTED` figure. No new status prose is added anywhere — a plainly reported,
plainly shown figure still says nothing about its status. What it gains is one
tier sentence.

This cannot flood the array. `figureStatusesOf` is already
`CostField.entries.mapNotNull { ... }`, so the ceiling is and stays
`CostField.entries.size` = **17**. What changes is how full the 17-slot array
gets: for a college the test seeder prices fully
(`CostsTestDb.seedCanonicalMoney`, 12 price fields + sticker + net price +
median debt + median earnings) the count goes from the handful of blank or
imputed cells — 0 to 3 in the current fixtures — to **12**, one per field with a
row: the eight published price cells the fixture writes, plus the sticker cost,
the net price, the median debt and the median earnings. (The draft said 16, by
counting twelve price fields; the seeder writes no row for the fees-only or
in-district fields unless a test asks for one, and no row means no note.)
`HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD` has no seeded row and so has no
entry, which is the rule the ceiling is made of: no row, no note. Both the
ceiling and the exact fixture count are pinned (see Tests).

**D7. On the wire the tier is a slug and a sentence, together.** Inside
`figureStatusObject` (`CollegeCostChatTool.kt:746-763`), beside `statement`
(`:750`) and `source` (`:755`), two new keys: `assurance` (the tier slug) and
`assurance_statement` (the sentence to say it in). This is the `income_band` +
`income_band_label` convention (RFC 151 D-D) applied a third time, and the same
reason RFC 177 D4 gave for `source`: a reader must not have to parse our English
to learn which tier a figure is on. `statement` is now ABSENT on a shown
reported figure rather than present and empty — absence has one representation.

The tool DESCRIPTION (`CollegeCostChatTool.kt:1673-1690`) gains two sentences
describing them. That is the prompt-facing contract and the one home for that
copy (RFC 142); a key the description does not name is a key the coach will not
speak.

**D8. No new prompt version.** `agentlessStatementOf`'s six sentences are
byte-frozen by the immutable v19 seed row
(`db/schema/0086.seed-coach-system-prompt-v19.sql:368-383`, walked by
`SystemPromptCatalogTest`), and `MoneySourceCopyTest.kt:165-171` pins
`statementOf`'s three delegating arms equal to the agentless answers. The tier
sentence is composed BESIDE both and is spliced into neither. Nothing in v23
becomes false: the prompt tells the coach to say a `figure_statuses` entry's own
sentence, and `assurance_statement` is one more sentence carried by the same
entry and described by the tool. No seed migration, no `service.conf` change.

**D9. The tier sentences name no publisher — deliberately, and not only because
of the grep.** `MoneyAttributionNamesNoPublisherTest.kt:139-152` sweeps
`service/src/main` and `public-web/src/main` for publisher names, and its
`ALIASES` (`:80`) include "Department of Education", so the spec's draft
`MANDATORY_SURVEY` sentence cannot be typed as a literal.

But composing `MoneySourceCopy.labelOf(source)` into it, which is how RFC 177
would solve that, would print a FALSE sentence: 16 of the 24 Scorecard strings
are `MANDATORY_SURVEY`, and "the college had to report this to the U.S.
Department of Education College Scorecard" is not what happened — the college
reported it to IPEDS and the Scorecard re-published it. The mandatory-survey
fact is about the FILING, which is the same act for all three of its publishers,
so the sentence states the act and names nobody: "the federal government". The
publisher is still named exactly once, by the seam that owns publisher names —
`FigureStatusCopy.statementOf`, in the sentence the tier sentence sits beside.
No tier sentence contains a banned literal and none retypes a publisher.

**D10. No number, no score, no rating — ever (brief D7, standing).** The tier is
a slug and a sentence. `IMPUTED_BY_PUBLISHER` is not a fourth tier: it is a
`FigureStatus`, it sits ON TOP of whichever tier the cell is on, and the two are
emitted as a pair. Money vocabulary as the corpus fixes it: "tuition and fees",
"housing and food", "the published price", "a financial aid offer"; no loan is
ever subtracted from a price; no bare source code appears in a tool result
without its sentence beside it.

## Detailed Design

### The enum and the resolver

`db/src/main/kotlin/ed/unicoach/db/models/AssuranceTier.kt`:

```kotlin
enum class AssuranceTier(val value: String) {
  /** A federal file about students, kept for another purpose. NSLDS, Treasury. */
  ADMINISTRATIVE_RECORD("administrative_record"),

  /** The school filed it because the law compels the filing, and the form edit-checks it. */
  MANDATORY_SURVEY("mandatory_survey"),

  /** The school published it about itself, on its own initiative, unaudited. */
  VOLUNTARY_SELF_REPORT("voluntary_self_report"),
  ;

  companion object {
    fun of(
      source: MoneySource,
      sourceVariable: String,
      location: String,
    ): AssuranceTier =
      when (source) {
        MoneySource.IPEDS_SFA -> MANDATORY_SURVEY
        MoneySource.IPEDS_IC_AY -> MANDATORY_SURVEY
        MoneySource.COMMON_DATA_SET -> VOLUNTARY_SELF_REPORT
        MoneySource.SCORECARD ->
          SCORECARD_TIERS[sourceVariable]
            ?: throw corruptValue(
              "source=[${source.value}] source_variable=[$sourceVariable]",
              "a tiered Scorecard source_variable (AssuranceTier.SCORECARD_TIERS)",
              location,
            )
      }

    /**
     * The 24 Scorecard strings this corpus loads; 16 IPEDS cells, published as 22 of these.
     *
     * PUBLIC, not `internal`: the closure walk that proves this map answers for
     * exactly the strings the loader writes lives in `:college`, where the
     * loader vocabularies are, and Kotlin `internal` is per-Gradle-module (D1).
     */
    val SCORECARD_TIERS: Map<String, AssuranceTier> = /* below */
  }
}
```

There is no `fromValue`. Nothing persists a tier and nothing reads one back out
of a store, so a decoder for it would be machinery with no reader.

`SCORECARD_TIERS` is written as one mapping and not as a `when`, because it is
data about another publisher's file:

| `source_variable`           | Dictionary `SOURCE` | Tier                    |
| --------------------------- | ------------------- | ----------------------- |
| `TUITIONFEE_IN`             | IPEDS               | `MANDATORY_SURVEY`      |
| `TUITIONFEE_OUT`            | IPEDS               | `MANDATORY_SURVEY`      |
| `ROOMBOARD_ON`              | IPEDS               | `MANDATORY_SURVEY`      |
| `ROOMBOARD_OFF`             | IPEDS               | `MANDATORY_SURVEY`      |
| `BOOKSUPPLY`                | IPEDS               | `MANDATORY_SURVEY`      |
| `OTHEREXPENSE_ON`           | IPEDS               | `MANDATORY_SURVEY`      |
| `OTHEREXPENSE_OFF`          | IPEDS               | `MANDATORY_SURVEY`      |
| `OTHEREXPENSE_FAM`          | IPEDS               | `MANDATORY_SURVEY`      |
| `COSTT4_A`                  | IPEDS               | `MANDATORY_SURVEY`      |
| `NPT4_PUB`, `NPT4_PRIV`     | IPEDS               | `MANDATORY_SURVEY`      |
| `NPT41_PUB` … `NPT45_PUB`   | IPEDS               | `MANDATORY_SURVEY`      |
| `NPT41_PRIV` … `NPT45_PRIV` | IPEDS               | `MANDATORY_SURVEY`      |
| `PCTPELL`                   | IPEDS               | `MANDATORY_SURVEY`      |
| `GRAD_DEBT_MDN`             | **NSLDS**           | `ADMINISTRATIVE_RECORD` |
| `MD_EARN_WNE_P10`           | **Treasury**        | `ADMINISTRATIVE_RECORD` |

24 strings: 8 into `price_figures` (`CanonicalMoneyLoader.kt:925-932`) and 16
into `cohort_money_stats` (`:985-1041`), of which the twelve `NPT4*` are the
control-keyed twins of six cells.

### The key space this has to be total over

| Source            | Distinct strings | Closed by                                                                                                                                   |
| ----------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `IPEDS_SFA`       | 82               | `SfaVariables.ALL` (`CollegeSfaLoader.kt:224-225`); the fill only looks up names it builds                                                  |
| `IPEDS_IC_AY`     | 48               | `IpedsChargeVocabulary.CELLS` (12 stems) × 4 suffixes; a stem outside `CELLS` is skipped, never written (`CanonicalMoneyLoader.kt:709-712`) |
| `SCORECARD`       | 24               | `ScorecardInstitutionColumns.LOADED_VARIABLES` — NEW in this RFC (D6a); the fill writes only through it                                     |
| `COMMON_DATA_SET` | 22               | `AID_POLICY_CELLS`'s field ids — NEW in this RFC (D5)                                                                                       |

176 strings. 154 were already closed at compile time; the 22nd source's set
becomes closed at ingest here. Bumping `IpedsChargeVocabulary.SURVEY_YEAR` does
not move the IC_AY set — the suffix is a position in the file's four-year
window, not a year (`IpedsChargeVocabulary.kt:20-27`) — so a snapshot bump does
not invalidate the tier map.

Only the `price_figures` and `cohort_money_stats` strings reach a reader through
today's read rows; `cohort_population_counts` and `aid_form_requirements` have
no read row carrying `source_variable`, and nothing in this RFC gives them one.

### Threading: one field, the RFC 177 shape

`sourceVariable` is non-null on both read rows (`CanonicalMoneyReadRows.kt:29`,
`:49`, decoded at `CanonicalMoneyReadDao.kt:194`, `:229`) and is discarded at
two lines where the winning row is in scope:

- `CollegeFigures.kt:410` —
  `DatedFigure(it.academicYear, it.reading, it.source)`
- `CollegeFigures.kt:466-473` —
  `DatedStat(vintage, residencyScope, reading, source = row.source)`

Each of the four carriers gains ONE non-null field, with no default, for exactly
the reason RFC 177 D4 gave for `source`: a default is how the last one got
dropped, and every construction site reads the value off a row it already holds.

`FigureStatusNote` is the exception to "the new field is `sourceVariable`": it
is not a read row, it is what a renderer is handed, and the cell id is the
resolver's INPUT rather than anything a renderer says. It carries the resolved
`assurance` only. The tier SENTENCE is a derived accessor over that tier and not
constructor state — a pure function of one field, held as a second field, is two
values a construction site can fill inconsistently.

| Carrier            | Declared at                 | New field                                                                     | Set at           |
| ------------------ | --------------------------- | ----------------------------------------------------------------------------- | ---------------- |
| `DatedFigure`      | `CollegeFigures.kt:302`     | `sourceVariable: String`                                                      | `:410`           |
| `DatedStat`        | `CollegeFigures.kt:341`     | `sourceVariable: String`                                                      | `:472`           |
| `FigureProvenance` | `CollegeFigures.kt:366`     | `sourceVariable: String`                                                      | `:603`, `:608`   |
| `FigureStatusNote` | `CollegeCostService.kt:714` | `assurance: AssuranceTier` (the tier sentence is DERIVED from it, not stored) | `:1944`, `:1971` |

`FitLensService.statusFields` takes the tier ALREADY RESOLVED and composes its
sentence into `netPriceNote`. The pair is resolved inside `readNetPrices` — the
read `netPricesForDigest` guards — and travels to the digest on a
`DigestNetPrice`: resolved at render time instead, one unreadable stored row
would have thrown out of the per-college loop and halted a whole pass after a
billed LLM call, where this read's declared contract is to degrade and continue.
It is called for EVERY row, shown or blank: the status fields used to hang on
the no-dollars branch alone, which would have put the tier on exactly the
figures the model never quotes and left the one net price it does quote with no
instrument behind it. A shown, plainly reported row says nothing about its
status, so it says its tier alone. `agentlessStatusFields` stays one-argument:
the no-row branch has neither a source nor a variable, and a tier for a row that
does not exist is the fabrication RFC 177 D4 refused.

**The composition itself lives in `AssuranceTierCopy`, once.** "Status sentence,
then tier sentence, and the tier sentence alone where there is no status prose"
is one decision. Written at each renderer it was written three times — a report
page cell, the shown-debt paragraph, and this digest — each with its own null
handling.

No `:db` change on this path. No migration. No new column.

### Reachability: where each sentence becomes visible

`figureStatusesOf` (`CollegeCostService.kt:1918-1946`) becomes:

```kotlin
val provenance = statusOf(field, served, band) ?: return@mapNotNull null
val status = provenance.status
val shown = !isNotReported(field, served, computed)
val assurance = AssuranceTier.of(provenance.source, provenance.sourceVariable)
// A shown, plainly reported figure says nothing NEW about its status -- the
// note it now gets exists to carry the tier, and inventing status prose for it
// is what D6 refuses. Every other case keeps RFC 177's sentence exactly.
val statement = if (shown && status == FigureStatus.REPORTED) null else FigureStatusCopy.statementOf(status, provenance.source)
FigureStatusNote(
  field = field,
  status = status,
  statement = statement,
  source = provenance.source,
  assurance = assurance,
)
```

`shown` implies the cell bears a value, so it implies `status` is `REPORTED` or
`IMPUTED_BY_PUBLISHER`; the imputed case keeps its RFC 177 sentence and now
carries a tier under it, which is the pair the brief asks for.

The three sentences first become visible at these exact places, which is the
spec's naming criterion:

- **`ADMINISTRATIVE_RECORD` — median federal debt
  (`SCORECARD`/`GRAD_DEBT_MDN`).** Report page: the shown debt paragraph,
  `CostReportPage.kt:978-983` — "Students who finished here carried a median of
  $X in federal loans. The source publishes no year for this figure." gains the
  tier sentence after it, read from the note for
  `MEDIAN_DEBT_AT_COMPLETION_USD`. Chat: the `figure_statuses` entry for
  `median_debt_at_completion_usd`, which the coach answers "how much debt do
  students leave with?" from (`CollegeCostChatTool.kt:445`).
- **`MANDATORY_SURVEY` — published in-state tuition and fees
  (`IPEDS_IC_AY`/`CHG2AY3`).** Chat: the `figure_statuses` entry for
  `tuition_and_fees_in_state_per_year_usd`, the answer to "what does it cost to
  go there?" (`putTuitionTiers`, `CollegeCostChatTool.kt:437`). Report page:
  ONLY through the blank arms the DOMAIN speaks for — an imputed, suppressed,
  not-applicable or year-gapped (`not_collected_by_us`) IPEDS charge cell, where
  `blankFor` prints the status sentence and the tier sentence after it
  (`CostReportPage.kt`, `blankFor`, reached from `tuitionBlankFor`, the
  component rows and the blended totals). NOT the ordinary
  `not_reported_by_institution` blank: that arm keeps the page's own cell-sized
  "Not reported by this school" with no tier, because a table cell whose whole
  content is two long sentences is a different page. And not beside a shown
  IPEDS amount either — the page prints a tier next to a shown figure at one
  site only, the debt paragraph above. The pinned example is
  `CostReportPageTest`'s suppressed books-and-supplies cell.
- **`VOLUNTARY_SELF_REPORT` — average percent of need met
  (`COMMON_DATA_SET`/`H.209`).** Chat only: the `aid_policy` object's
  `average_need_met_percent`/`average_need_met_label` pair
  (`AidPolicyWire.kt:65-66`), emitted at `CollegeCostChatTool.kt:592`. This is
  the slice's first-session test — "does Amherst meet full financial need?" —
  and the report page renders no aid-policy section at all, so there is no page
  line to name for this tier.

On the page's blank path nothing new is plumbed: `blankFor` already prints
`note.statement` at `:672`, and it now prints the tier sentence after it. Its
`REPORTED` arm, which the comment at `:666-668` says cannot be reached, becomes
reachable-in-principle now that a shown figure has a note, so it returns the
page's own `otherwise` words rather than a null statement: a note that says
nothing about a status may not blank a cell.

### The softest tier's read path, and a spec correction

**Correction, factual and not a re-decision.** The slice says the softest
sentence belongs to "CDS H.208/H.209". `H.208` is `need_fully_met_count` — a
HEADCOUNT, landing in `cohort_population_counts` (`bin/fetch-cds-seed:293`,
`CdsSeedLoader.kt:1327-1328`) — and that table has no read row carrying
`source_variable`, so `H.208` cannot reach this seam at all. The softest
sentence is pinned to **`H.209`** (`avg_need_met_percent` →
`MoneyMeasure.AVG_NEED_MET_SHARE`, `CdsSeedLoader.kt:1312-1317`) and to
**`H.211`** (`avg_need_based_grant_usd`), which is served through the same door.

`AidPolicyDao.listLatest` (`AidPolicyDao.kt:50-146`) reads `cohort_money_stats`
with `s.source = cds.source` bound to `MoneySource.COMMON_DATA_SET.value`
(`:127`) and selects `s.measure AS name, s.value AS number` (`:85`). It gains
`s.source` and `s.source_variable` on the stat arm of the `facts` CTE, and the
two sibling arms select their own too — both columns are `NOT NULL` on all three
tables, so a `NULL::text` there would be a lie about the row.

`mapFact` resolves the tier on the spot, from BOTH halves read off the row:

```kotlin
STAT_KIND -> CdsAidFact.Stat(
  measure = decodeMeasure(name, row),
  figure = number,
  assurance = AssuranceTier.of(decodeSource(rs.getString("source"), row), rs.getString("source_variable")),
)
```

The publisher is decoded, not asserted as `MoneySource.COMMON_DATA_SET`. The
read binds that slug as a WHERE filter a hundred lines above, but a filter is
not a value: half a pair read off the row and half re-typed at the mapper is a
pair that can disagree with the row it claims to describe.

`CollegeAidPolicy` does NOT gain a tier side-map. Each of its two averages
becomes an `AssuredFigure<T>` — the value and its tier as ONE value. A parallel
`Map<MoneyMeasure, AssuranceTier>` beside the figures has keys that need not
match the record's own fields: `averageNeedMet != null` with an empty map
compiles, and the wire writer's `?: return` then drops the tier of a figure it
is emitting in the same breath, silently. Enveloped, a figure without a tier is
not a value anyone can build. It is the `FullyMetNeedCounts` rule one size
smaller: what may not be published apart must not be constructible apart.
`AidPolicyPractice` carries the two enveloped figures through unchanged.
`AidPolicyWire.objectOf` emits, beside each figure it already emits:

- `average_need_met_assurance` + `average_need_met_assurance_statement`, beside
  `NEED_MET_SHARE_KEY`/`NEED_MET_LABEL_KEY` (`:65-66`);
- `average_need_based_grant_assurance` +
  `average_need_based_grant_assurance_statement`, beside `AVERAGE_GRANT_KEY`.

Neither new key is numeric, so `AidPolicyWire.NUMERIC_KEYS` (`:53-55`) and the
RFC 143 guard are untouched. The headcounts and the form flags get no tier: they
are not money figures and, for the headcounts, `H.204`/`H.208` reach the wire
through a derived share whose tier would be a claim about two rows at once.

One resolver, called from `:service` for canonical money figures and from `:db`
for the aid-policy read. No second mapping, no measure-keyed special case.

### The copy

`service/.../coaching/costs/canonical/AssuranceTierCopy.kt`:

```kotlin
object AssuranceTierCopy {
  /** How a family hears what KIND of number this is. Composed beside the status sentence, never inside it. */
  fun statementOf(tier: AssuranceTier): String =
    when (tier) {
      AssuranceTier.ADMINISTRATIVE_RECORD ->
        "This comes from federal loan and tax records, not from the college. It covers only students " +
          "who got federal aid, and small numbers are blurred a little to protect privacy."
      AssuranceTier.MANDATORY_SURVEY ->
        "The college had to report this to the federal government, and the form checks it against last " +
          "year's answer. Nobody audits it."
      AssuranceTier.VOLUNTARY_SELF_REPORT ->
        "The college published this about itself. No one checks it. We link to the college's own file " +
          "so you can see it."
    }
}
```

Exhaustive, no `else`: a fourth tier fails to compile here rather than shipping
wordless. The wording is the spec's own, at its 8th-grade reading level, with
the one change D9 argues: "the federal government" in place of the publisher
name the spec drafted, because 16 of the mandatory-survey strings are relayed by
a publisher the college never filed with.

A tier sentence and a status sentence are always composed as two sentences, in
that order — status first (whose act), tier second (what kind of number) — and
never concatenated into one. On the wire they are two keys and the consumer
composes nothing.

## Anti-vacuity

Three arms, three different sentences, three figures a family actually sees —
one per tier, each traced from a stored row to a rendered line:

1. **`ADMINISTRATIVE_RECORD` — median federal debt at completion.**
   `MoneySource.SCORECARD` + `GRAD_DEBT_MDN`, written at
   `CanonicalMoneyLoader.kt:1026-1033`; dictionary `SOURCE` = NSLDS. Visible on
   the report page at `CostReportPage.kt:978-983` and in chat at
   `CollegeCostChatTool.kt:445`. Sentence: "This comes from federal loan and tax
   records, not from the college…"
2. **`MANDATORY_SURVEY` — published in-state tuition and fees.**
   `MoneySource.IPEDS_IC_AY` + `CHG2AY3` (stem `CHG2AY`,
   `IpedsChargeVocabulary.kt:74`; string built at
   `CanonicalMoneyLoader.kt:729-730`). Visible in chat through `putTuitionTiers`
   (`CollegeCostChatTool.kt:437`), and on the report page only through the blank
   arms the domain speaks for — an imputed, suppressed, not-applicable or
   year-gapped IPEDS charge cell, where `blankFor` prints the tier sentence
   after the status one — never beside a shown tuition amount. Sentence: "The
   college had to report this to the federal government…"
3. **`VOLUNTARY_SELF_REPORT` — average percent of need met.**
   `MoneySource.COMMON_DATA_SET` + `H.209`, seeded at
   `db/seed/cds/aid_policy.csv:3` and mapped at `CdsSeedLoader.kt:1312-1317`.
   Visible in chat in the `aid_policy` object (`AidPolicyWire.kt:65-66`).
   Sentence: "The college published this about itself. No one checks it…"

The three are on three different publishers, three different tables' read paths
and two different surfaces, so a test that asserts them cannot pass by accident
on one fixture. The test asserts BOTH halves: the three sentences are pairwise
distinct, and each of the three named figures resolves to its own tier — a map
that answered one tier for everything would fail the second half, and a copy
object that answered one sentence for everything would fail the first.

The pair rule gets its own case: an IMPUTED IPEDS cell emits
`imputed_by_publisher` + `mandatory_survey` and both sentences, while a
`REPORTED` CDS figure emits `voluntary_self_report` with no status sentence at
all. Neither collapses into the other.

## Test-count evidence

`research-seam.md` enumerated 19 pinned-word assertions across 5 files that
would move if a tier sentence were appended INTO
`FigureStatusCopy.statementOf`'s return value, and 20 that survive if it is
composed BESIDE. This RFC composes beside, so the expected movement is:

- **Unchanged (the 20).** `MoneySourceCopyTest.kt:222-242` (the six agentless
  literals, and the three delegating arms asserted equal to them),
  `SystemPromptCatalogTest.kt:1679-1682`, `:1944-1950`, `:1963-1968` (the v19
  and v23 enum walks — no prompt version is added, so these are untouched),
  `CollegeFiguresTest.kt:605-615`, `CostReportPageTest.kt:863`.
- **Expected to move, and why.**
  - `CollegeCostChatToolTest.kt` — every assertion that counts or indexes
    `figure_statuses` entries, because a shown figure now has one (D6). The RFC
    177 defect test at `:2422-2454` keeps its exact `statement`, since that cell
    is suppressed and not shown.
  - `CollegeCostServiceTest.kt:1239`, `:1246-1257`, `:1353-1357` — the same
    counting change on the service's own note list.
  - `FitLensServiceTest.kt:1228-1235`, `:1332-1335`, `:1356-1361` —
    `netPriceNote` gains the tier sentence after the status sentence.
  - `CostReportPageTest.kt` blank-cell assertions that pin a cell's whole text.
  - `CdsSeedLoaderTest.kt` gains the field-id refusal; `:373-384` keeps
    `assertEquals("fact", defect.column)` unchanged, which is why D5 orders the
    two checks the way it does.

Every moved assertion is reported in the implementation summary with its file,
its line and the sentence that moved, alongside the executed counts.

## What this does not do

- **No confidence score, percentage, star rating or ranking**, now or ever
  (brief D7, standing). The tier is three words and three sentences.
- **No coherence checking.** Whether the CDS freshman count agrees with IPEDS
  `SCFA1N` is brief D4's ingest work, not a tier.
- **No vintage fix.** The year a citation states is `soft/03/the-year-we-cite`.
- **No per-document assurance**, no `publisher_flag` letter on the wire. The
  finer imputation fact (`CARRY_FORWARD` vs `GROUP_MEDIAN`) is on the read rows
  already (`CanonicalMoneyReadRows.kt:30,50`) and reachable later without a
  schema change; it is not this slice.
- **No tier on the aggregate surfaces.** `CollegeCost.moneySources` and
  `sourcesSection` are `distinct()` over a whole college's figures
  (`CollegeFigures.kt:636-642`), so they cannot honestly carry a per-figure
  tier, and this RFC does not give them one.
- **No tier for `cohort_population_counts` or `aid_form_requirements`.** No read
  row carries their `source_variable`; a tier there would be data with no
  reader.
- **No prompt version, no `service.conf` change, no DDL, no seed migration.**
- **`DEBT_MDN`** is not mapped: this corpus does not load it.

## Files Modified

| File                                                | Change                                                                                                                                                                            |
| --------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/.../models/AssuranceTier.kt`                    | NEW — the enum, `of(source, sourceVariable)`, and `SCORECARD_TIERS`                                                                                                               |
| `db/.../models/CollegeAidPolicy.kt`                 | NEW `AssuredFigure<T>(value, assurance)`; both averages enveloped in it                                                                                                           |
| `db/.../dao/AidPolicyDao.kt`                        | select `source` and `source_variable` on all three `facts` arms; decode both and resolve the tier in `mapFact`                                                                    |
| `college/.../ScorecardInstitutionColumns.kt`        | NEW `LOADED_VARIABLES` registry + `loadedVariableOf` guard (D6a)                                                                                                                  |
| `college/.../CanonicalMoneyLoader.kt`               | both Scorecard fills write their cell id through `loadedVariableOf`                                                                                                               |
| `college/.../CdsSeedLoader.kt`                      | `AID_POLICY_FACTS` becomes `AID_POLICY_CELLS: Map<String, AidPolicyCell>` carrying `(fact, fieldId)`; field-id check after the `fact` lookup, taking the decoded cell             |
| `service/.../costs/canonical/AssuranceTierCopy.kt`  | NEW — the three sentences, and `composedStatementOf` (status sentence then tier sentence, once)                                                                                   |
| `service/.../costs/canonical/CollegeFigures.kt`     | `sourceVariable` on `DatedFigure` (`:302`), `DatedStat` (`:341`), `FigureProvenance` (`:366`); set at `:410`, `:472`, `:603`, `:608`                                              |
| `service/.../costs/CollegeCostService.kt`           | `FigureStatusNote` gains `assurance` and a DERIVED tier sentence, `statement` becomes nullable; `figureStatusesOf` stops dropping shown notes; `yearGapOf` resolves the tier once |
| `service/.../costs/CollegeCostChatTool.kt`          | `assurance` + `assurance_statement` in `figureStatusObject` (`:746-763`); key constants beside `:1312`; DESCRIPTION (`:1673-1690`)                                                |
| `service/.../costs/AidPolicyPractice.kt`            | carry the two `AssuredFigure` averages through `from`                                                                                                                             |
| `service/.../costs/AidPolicyWire.kt`                | four keys and their sentences beside the two need figures (`:62-101`)                                                                                                             |
| `service/.../fitlens/FitLensService.kt`             | the tier is resolved inside the guarded read (`DigestNetPrice`); `statusFields` takes it resolved and is called for EVERY row, so a SHOWN net price carries its tier too          |
| `public-web/.../render/CostReportPage.kt`           | `blankFor` and the shown debt paragraph both call `AssuranceTierCopy.composedStatementOf`                                                                                         |
| `db/seed/scorecard/dictionary-variable-sources.csv` | NEW — one transcribed `variable_name,source` row per string the loader writes (D4)                                                                                                |
| `db/seed/scorecard/FETCH-NOTES.md`                  | the dictionary's citation, version, bytes and the CSV's sha256                                                                                                                    |
| `.gitignore`                                        | `!db/seed/scorecard/dictionary-variable-sources.csv` (`:54-56`)                                                                                                                   |
| tests                                               | below                                                                                                                                                                             |

## Implementation Plan

1. `AssuranceTier` + `SCORECARD_TIERS` in `:db`, with the fatal. No callers yet.
   Unit tests for the four arms and the fatal.
2. The closure work in `:college`: `AID_POLICY_CELLS`, the field-id check in the
   right order, `ScorecardInstitutionColumns.LOADED_VARIABLES` with the fill
   bound to it, and the three derived-set walks (SFA, IC_AY, Scorecard)
   asserting every member resolves.
3. The committed transcription: the CSV, the `FETCH-NOTES.md` stanza, the
   `.gitignore` exception, and the dictionary-agreement test.
4. `AssuranceTierCopy` in `:service`, with its own unit tests and the
   no-publisher-name sweep passing unchanged.
5. Thread `sourceVariable`: `CollegeFigures` carriers, `statusOf`,
   `FigureStatusNote`, `FitLensService`.
6. Reachability: `figureStatusesOf` emits a note for a shown figure; `statement`
   becomes nullable; the chat keys, the tool DESCRIPTION, the page's two sites.
7. The aid-policy path: `AidPolicyDao` select and `mapFact`, `CollegeAidPolicy`,
   `AidPolicyPractice`, `AidPolicyWire`.
8. Full `nix develop -c bin/test`; report the executed counts and every pinned
   assertion that moved, with its reason.

## Tests

- **Totality over the real key space.** Three walks in `:college`, each deriving
  its set from the constants the loader itself reads rather than retyping them:
  `SfaVariables.ALL` (82), `IpedsChargeVocabulary.CELLS.keys` ×
  `SUFFIX_BY_ACADEMIC_YEAR.values` (48), and
  `ScorecardInstitutionColumns.LOADED_VARIABLES`. Every member resolves, and
  each walk asserts the expected tier, so a whole-source arm cannot silently
  answer for a variable it should not.

  The Scorecard set is **derived, not hand-listed**, and that is the whole of
  D6a. Hand-listed, a thirteenth column added to `mapScorecardPrices` or
  `mapScorecardStats` would have left every assertion here green and then thrown
  at read time, because the list this compares the tier map against would not
  have been the set the loader writes. The fill now writes every Scorecard cell
  id through `ScorecardInstitutionColumns.loadedVariableOf`, which refuses an
  unregistered string, so "every `source_variable` the loader CAN WRITE resolves
  to exactly one tier" holds by construction rather than by a typed list.
- **The unmapped variable is fatal.** An invented `SCORECARD` variable throws a
  located `CorruptPersistedValueException` carrying both halves of the pair and
  the caller's locator, and the message names the source slug and the variable.
  Asserted as a throw, never as a fallback value — a test that accepted a
  default would be the defect.
- **The Common Data Set set is closed at ingest.** Fact and field id are one
  value (`AidPolicyCell`), so a fact without an id does not compile and there is
  nothing left for a test to assert about the pairing; a fixture row whose
  `source_variable` is not its `fact`'s field id is refused with
  `Defect.UnknownCode` naming column `source_variable`; and
  `cds-aid-policy-unknown-fact-fixture.csv` still fails on column `fact`,
  unchanged (`CdsSeedLoaderTest.kt:373-384`). A walk over the committed
  `db/seed/cds/aid_policy.csv` asserts all 22 distinct field ids resolve, using
  the `committedSeedDir` walk-up that file's tests already use
  (`CdsSeedLoaderTest.kt:57-60`).
- **The falsifiable dictionary pin (the spec's own criterion).** A test reads
  `db/seed/scorecard/dictionary-variable-sources.csv` with the commons-csv
  reader already on `:college`'s classpath (`college/build.gradle.kts:18`) and
  asserts, column by column, that `SCORECARD_TIERS` agrees with it: 24 rows,
  exactly the 24 keys of the map, `SOURCE == "IPEDS"` on the 22 strings of the
  16 IPEDS cells, `NSLDS` on `GRAD_DEBT_MDN`, `Treasury` on `MD_EARN_WNE_P10`. A
  companion assertion pins the file's sha256 against the digest recorded in
  `FETCH-NOTES.md`, so the transcription cannot be edited quietly. If NCES
  re-sources a column, the re-transcription fails this test and the tier is
  known to be wrong.
- **Anti-vacuity, both halves.** The three sentences are pairwise distinct and
  non-blank; each of the three worked figures above resolves to its own tier on
  a real seeded row; and a walk over `AssuranceTier.entries` asserts every
  member has a sentence.
- **The pair rule.** An imputed IPEDS cell emits `imputed_by_publisher` AND
  `mandatory_survey`, with both sentences; a reported CDS figure emits
  `voluntary_self_report` and no status sentence.
- **The array does not flood (D6).** For a college the seeder prices fully,
  `figure_statuses` goes from the blank/imputed handful to **12** entries — one
  per `CostField` with a row: the eight published price cells the fixture
  writes, plus the sticker cost, the net price, the median debt and the median
  earnings — and the array is asserted never to exceed `CostField.entries.size`
  (**17**), pinned as `entries.size` and not as a typed 17. A field with no row
  still produces no entry.
- **The shown figure says nothing new about its status.** A shown, reported
  figure's entry has NO `statement` key and DOES have `assurance` and
  `assurance_statement`. The page's shown debt paragraph carries the
  administrative-record sentence; the page's blank cells carry status sentence
  then tier sentence, in that order.
- **The publisher sweep still passes.** `MoneyAttributionNamesNoPublisherTest`
  is unchanged and unexempted: no tier sentence contains a banned literal, and
  `AssuranceTierCopy` is not added to its `seam` exception.
- **`agentlessStatementOf` is untouched.** Its six literals keep their pinned
  values and `statementOf`'s three delegating arms stay equal to them
  (`MoneySourceCopyTest.kt:165-171`, `:222-242`).
- **The aid-policy read.** `H.209` and `H.211` reach the wire with
  `voluntary_self_report` and its sentence, resolved through `AssuranceTier.of`
  on the real `AidPolicyDao` read, pinned on the DB-backed
  `CollegeCostChatToolTest` case ("the two Common Data Set averages carry the
  softest tier, resolved from the row's own field id") — `:db` holds no
  `AidPolicyDaoTest` to extend, and this case additionally pins the wire keys. A
  filing with only form rows emits no tier key at all.
- `nix develop -c bin/test` full suite; executed counts reported.

## Open question for approval

Two places where this RFC departs from the ship instruction, both for a stated
reason, both cheap to reverse:

1. **The `MANDATORY_SURVEY` sentence says "the federal government" and composes
   no `MoneySourceCopy.labelOf`** (D9). Composing the label would print "the
   college had to report this to the U.S. Department of Education College
   Scorecard" for 16 of the 24 Scorecard strings, which is false — the college
   filed with IPEDS and the Scorecard relayed it. If the label must be composed,
   the honest alternative is a fourth arm about relayed cells, which is a bigger
   change than this slice.
2. **The dictionary digest is recorded in `db/seed/scorecard/FETCH-NOTES.md`,
   not in `PROVENANCE.json`** (D4), because `bin/fetch-scorecard:1601-1605`
   rewrites `PROVENANCE.json` whole and `FETCH-NOTES.md:14` states that nothing
   in it is hand edited.

The coach needs no prompt change for the two new chat keys: the tool DESCRIPTION
is the prompt-facing contract for tool result keys (RFC 142), and v23 already
tells the coach to say a `figure_statuses` entry's own sentence. No v24.
