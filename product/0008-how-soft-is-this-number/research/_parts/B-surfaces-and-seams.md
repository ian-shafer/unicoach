# Part B — the hand-written hedge sites in code, and the seams

Scope: everything OUTSIDE the coach system prompt. Read-only static analysis of
the live tree (`.claude/worktrees/**` ignored). Every claim carries `file:line`
from `grep -n` / `awk` on the live files.

**Headline finding (the one that decides C1..C6):** `MoneySource` — the
four-member publisher enum the brief calls "the source axis" — **never reaches a
rendering surface**. Counted with
`grep -rn "MoneySource" --include=*.kt . | grep -v worktrees | grep -v /build/`,
grouped by file: 25 files, and the sets are `db/` (5 files), `college/` (7
files), tests (13 files). **Zero** hits in `service/src/main/kotlin/**` and
**zero** in `public-web/src/main/kotlin/**`. The read row _carries_ it —
`db/src/main/kotlin/ed/unicoach/db/models/CanonicalMoneyReadRows.kt:28` and
`:48` both declare `val source: MoneySource,` — and the service layer drops it
on the floor. So today **no renderer can branch on the publisher even if it
wanted to.** Every hedge below is therefore either (a) a constant that names one
publisher by hand, or (b) a sentence that carefully avoids naming any publisher
at all.

---

## 1. `service/src/main/kotlin/ed/unicoach/coaching/**`

### 1.1 The single-publisher attribution constant (2 call sites, 1 string)

```
service/.../coaching/costs/SingleSchoolBasis.kt:224  object CostSources {
service/.../coaching/costs/SingleSchoolBasis.kt:225    const val SCORECARD_ATTRIBUTION = "U.S. Department of Education College Scorecard"
```

Re-exported once for the chat tool:

```
service/.../coaching/costs/CollegeCostChatTool.kt:1125  const val SOURCE_ATTRIBUTION = CostSources.SCORECARD_ATTRIBUTION
```

and consumed verbatim by the report page (§2). This is the whole of the money
payload's provenance claim: **one string, one publisher, no per-figure axis.**
The tool description repeats the claim in prose:

```
CollegeCostChatTool.kt:1450  "Data comes from the U.S. Department of Education College Scorecard - always attribute figures " +
CollegeCostChatTool.kt:1451    "to it, and when a field appears in $DATA_AVAILABILITY_KEY there is no number for it in this result: " +
```

**What breaks with a 5th source:** this is the sharpest break in the tree. The
`source` key on the payload is a per-_payload_ string, not per-figure
(`CollegeCostChatTool.kt:574` in test asserts
`assertEquals("U.S. Department of Education College Scorecard", result.getValue("source")...)`).
Today it is _already_ a half-lie: since RFC 161/162 the prices in that payload
may be `ipeds_ic_ay` or `ipeds_sfa` rows (`CanonicalMoneyLoader.ORDERED_SOURCES`
puts IPEDS SFA, then IPEDS IC_AY, then the Scorecard —
`college/.../CanonicalMoneyLoader.kt:97-99`), and the payload still says
"College Scorecard". A 5th publisher does not create the defect; it widens one
that is live now.

### 1.2 `CdsCitation` — the per-section citation renderer

```
service/.../coaching/admissions/CdsCitation.kt:35
  val citedAs: String get() = "$collegeName's ${cycleLabel(sourceYear)} Common Data Set"
service/.../coaching/admissions/CdsCitation.kt:51-55
  fun JsonObjectBuilder.putCitation(citation: CdsCitation) {
    put("cited_as", citation.citedAs)
    put("url", citation.url)
    citation.archiveUrl?.let { put("archive_url", it) }
  }
```

The publisher name `" Common Data Set"` is **string-concatenated into the
type**. The type is named for one publisher, its `citedAs` hard-codes that
publisher's name, and `cycleLabel` hard-codes that publisher's year convention
(`sourceYear -> "2024-25"`, `CdsCitation.kt:46`). Two callers construct it:

```
service/.../coaching/costs/AidPolicyPractice.kt:104-110   source = CdsCitation(collegeName=..., sourceYear=row.academicYear.firstCalendarYear, url=..., archiveUrl=...)
```

(plus the merit-aid path via `MeritAidWire`,
`MeritAidWire.kt:67  putJsonObject("source") { putCitation(merit.source) }`).

**What breaks with a 5th source:** `CdsCitation` is the closest thing in the
repo to C5's "one derived hedge renderer" — the brief says so — but it is a
_publisher-specific_ renderer, not a generic one. A 5th source needs either (i)
a second `XxxCitation` type + a second `putXxxCitation` (duplication), or (ii)
`CdsCitation` generalised to carry a `MoneySource` and derive the name and year
convention from it. (ii) is cheap and is the natural first slice of C5. Note
`source_documents` already holds `val source: MoneySource,`
(`db/.../models/SourceDocument.kt:34`) — the data for (ii) exists; nothing reads
it at the render layer.

### 1.3 `FigureStatusCopy` — six statuses, six sentences (the real seam)

`service/.../coaching/costs/canonical/FigureStatusCopy.kt`. Verbatim, all five
non-null statements:

| line  | status                        | string                                                                                      |
| ----- | ----------------------------- | ------------------------------------------------------------------------------------------- |
| 50-51 | `REPORTED`                    | `null` (no sentence at all)                                                                 |
| 54    | `IMPUTED_BY_PUBLISHER`        | `"This is the publisher's own estimate for this school, not a figure the school reported."` |
| 58    | `SUPPRESSED_BY_PUBLISHER`     | `"This figure is withheld by the publisher for privacy."`                                   |
| 62    | `NOT_REPORTED_BY_INSTITUTION` | `"This school did not report this figure."`                                                 |
| 66    | `NOT_APPLICABLE`              | `"This figure does not apply at this school, and the source says so."`                      |
| 70    | `NOT_COLLECTED_BY_US`         | `"We have not collected this figure yet."`                                                  |

plus the year-gap sentence, built from two years:

```
FigureStatusCopy.kt:148-149
  "We show this school's prices for $servedYear, and the most recent year we hold this figure for is " +
    "$heldYear. We do not mix years inside one total, so it is not shown here."
```

and a three-member owner axis (`FigureGapOwner`, `FigureStatusCopy.kt:16-25`:
`SCHOOL` / `PUBLISHER` / `UNICOACH`) with `ownerOf` (`:75`),
`isSchoolsOwnSilence` (`:101`) and `noTotalReasonOf` (`:118`).

Two observations that matter for the decision:

1. **`FigureStatus.REPORTED` returns `null` — that is exactly the hole the brief
   is about.** "A plainly reported figure is shown plainly and needs no sentence
   beside it" (`FigureStatusCopy.kt:44-45`). A CDS self-report and a Scorecard
   NSLDS figure both land here, and both get _no sentence_.
2. Every one of these sentences says "the publisher" / "the source" —
   **agentless on purpose**. So they do NOT break when a 5th source is added:
   they are already publisher-neutral. `FigureStatusCopy` is the one place in
   the tree already shaped to take an assurance axis without a copy rewrite per
   publisher.

### 1.4 `AidPolicyWire` / `AidPolicyPractice` (CDS-shaped, RFC 170)

```
service/.../coaching/costs/AidPolicyWire.kt:144-147
  const val FORMS_NOT_COLLECTED_NOTE: String =
    "This school's Common Data Set answers these forms and we could not read its answer, so we do not know " +
      "whether they are required. Say that we could not read it -- never that the school does not require them " +
      "-- and send the family to the school's financial aid office."

service/.../coaching/costs/AidPolicyWire.kt:156-159
  const val FORMS_NOTE: String =
    "These are the aid forms this school's Common Data Set lists as required of first-year applicants for aid. " +
      "A form that is not listed is not listed in that filing -- which is not the same as the school saying it " +
      "is not required. Tell the family to check with the financial aid office rather than assuming."
```

Three more spoken labels in the same object, each naming its denominator rather
than its provenance: `needMetLabel` (`:113-115`), `fullyMetLabel` (`:127-133`),
`averageGrantLabel` (`:136-137`). `AidPolicyPractice` carries no copy — it
carries `val source: CdsCitation` (`AidPolicyPractice.kt:52`) and the `hasFact`
predicate (`:75-84`) that decides _which_ of the two silences below is spoken.

### 1.5 The two aid-policy coverage sentences (`CollegeCostChatTool`)

```
CollegeCostChatTool.kt:1278-1281
  const val AID_POLICY_NO_FILING: String =
    "We hold no Common Data Set filing for this school, so we have nothing to say about how it treats " +
      "financial need or which aid forms it requires. Say that plainly and point the family at the school's " +
      "financial aid office; never estimate it from another school."

CollegeCostChatTool.kt:1289-1292
  const val AID_POLICY_NO_FACT_IN_FILING: String =
    "We hold this school's Common Data Set filing, but it reports none of the need figures or aid forms we " +
      "read out of it. Say that we have nothing on this school's aid policy rather than that the school " +
      "requires nothing, and point the family at its financial aid office."
```

These are the two sentences the brief's RFC-170 failure mode is about ("a school
whose filing we hold told 'we hold no filing'").

**What breaks with a 5th source (1.4 + 1.5):** all four constants hard-code
`"Common Data Set"`. Four sentences × one publisher. A 5th publisher of
aid-policy facts needs four more constants, or the publisher name lifted out of
them. There is no `sourceName(MoneySource)` function anywhere in the tree — I
grepped for one; the nearest thing is `MoneySource.value`
(`db/.../models/MoneySource.kt:27,30,33,42`), which is a wire slug
(`"ipeds_sfa"`, `"common_data_set"`), **not** a spoken label. **No spoken label
for a publisher exists as data anywhere.**

### 1.6 Enum-carried hedge labels (the "code + words in one home" pattern)

The cost domain's convention is that every code ships its sentence from the same
construct. Every one of these is hand-written English on an enum member:

| type                           | file:line                                                               | hedge strings                                                                                                                                                                                                           |
| ------------------------------ | ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WithheldReason.statement`     | `BlendedFigureBasis.kt:52-55`                                           | `"This school publishes this figure for students paying in-state tuition, and this family would pay the out-of-state price here, so it is not shown for them. The source publishes no out-of-state version of it. ..."` |
| `WithheldReason.cellPhrase`    | `BlendedFigureBasis.kt:75`                                              | `"this school publishes this figure for in-state students. Your family would pay the out-of-state price"`                                                                                                               |
| `ArrangementGap.phrase`        | `ComparisonBasis.kt:639`                                                | `NO_ON_CAMPUS_HOUSING("no_on_campus_housing", "no residence halls")`                                                                                                                                                    |
| `ArrangementGap.phrase`        | `ComparisonBasis.kt:656`                                                | `NOT_REPORTED("not_reported", "no published price for it")`                                                                                                                                                             |
| `NoTotalReason.phrase`         | `CostBreakdown.kt:655`                                                  | `"no total yet, because we have not been told which state the student is a resident of"`                                                                                                                                |
|                                | `CostBreakdown.kt:666`                                                  | `"no total, because we cannot tell which of this school's published prices applies"`                                                                                                                                    |
|                                | `CostBreakdown.kt:677`                                                  | `"no total, because a part of what that way of living costs is not published"`                                                                                                                                          |
|                                | `CostBreakdown.kt:695`                                                  | `"no total, because we have not collected a part of what that way of living costs"`                                                                                                                                     |
| `LineOrigin`                   | `CostBreakdown.kt:182,185`                                              | `PUBLISHED("published")` / `ASSUMED_BY_UNICOACH("assumed_by_unicoach")` — codes only, the words are at the render sites                                                                                                 |
| `SingleSchoolBasis` statements | `SingleSchoolBasis.kt:74,76-77,114-115,119-120,157-158,167-168,177-178` | e.g. `"The published price and the price after a financial aid offer shown for this school come from the $academicYear academic year, and are averages blended across the ways of living."`                             |
| at-home assumption             | `ComparisonBasis.kt:137-139`                                            | `"Living at home, we count no food-and-housing cost: ... That is our assumption, not a figure any school published."`                                                                                                   |
| fit-lens fallback              | `FitLensService.kt:138`                                                 | `private const val NOT_REPORTED = "not reported"` (used at `:835` as the fallback for `FigureStatusCopy.statementOf(status) ?: NOT_REPORTED`)                                                                           |

**Count:** in `service/src/main/kotlin/ed/unicoach/coaching/**` I count **19
distinct hand-written hedge strings** (5 status statements + 1 year-gap
builder + 4 `NoTotalReason` phrases + 2 `ArrangementGap` phrases + 2
`WithheldReason` forms + 4 CDS-naming constants + 1 at-home assumption), plus 1
attribution constant and ~7 `SingleSchoolBasis`/`ComparisonBasis` basis
statements that are about _year/cohort_, not provenance. None of them is derived
from `MoneySource`, because `MoneySource` is not in scope in this module.

**Note on the axis these strings already encode:** `FigureGapOwner`
(`FigureStatusCopy.kt:16-25`) is a _three-way_ SCHOOL / PUBLISHER / UNICOACH
split. That is an axis about _who is silent_, not _how the number was produced_.
The two are orthogonal, and nothing in the tree confuses them — a C2 assurance
tier would be a genuinely new axis, not a rename of this one.

---

## 2. `public-web/.../render/CostReportPage.kt` (985 lines)

The parent-facing page. Its hedge copy is `private const val` at the top of the
file, plus one imported domain phrase.

| line    | constant                       | string                                                                                                                                                                                 |
| ------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 96      | `NOT_REPORTED` (`internal`)    | `"Not reported by this school"`                                                                                                                                                        |
| 99      | `TUITION_WITHHELD`             | `"Not shown — the state the family lives in is not on file"`                                                                                                                           |
| 123     | `withheldBlankFor(...)`        | `"Not shown — ${reason.cellPhrase} — $totals."` — **the reason is the domain's**, imported from `WithheldReason.cellPhrase`                                                            |
| 126     | `TOTALS_ABOVE`                 | `"the totals above"`                                                                                                                                                                   |
| 129     | `TOTALS_IN_SCHOOL_TABLE_BELOW` | `"the totals in this school's own table below"`                                                                                                                                        |
| 140     | `NOT_PART_OF_ARRANGEMENT`      | `"Not a part of this way of living"`                                                                                                                                                   |
| 153     | `ASSUMED_BY_US`                | `"our assumption, not a figure this school published"`                                                                                                                                 |
| 156     | `NO_TOTAL`                     | `"No total — a part of this price is missing"`                                                                                                                                         |
| 159     | `ASSUMPTIONS_HEADING`          | `"What these figures assume"`                                                                                                                                                          |
| 198-200 | `SUMMARY_NO_HELD_ARRANGEMENT`  | `"No one way of living is priced at every school here, so this table compares only each school's own school-wide figures; ..."`                                                        |
| 203     | `SUMMARY_TUITION_VARIES`       | `"Quoted per way of living below"`                                                                                                                                                     |
| 214-216 | `BLENDED_IN_STATE_CLAUSE`      | `"At a public school it, and the likely price after a financial aid offer, are figures for students paying in-state tuition."`                                                         |
| 219-221 | `SUMMARY_HINT`                 | `"The published price is each school's own published cost of attendance, an average blended across the ways of living, so it is not the sum of the parts quoted below. "` + the clause |
| 224-226 | `SCHOOL_BLENDED_HINT`          | the same, `"this school's"`                                                                                                                                                            |
| 948     | inline                         | `"This school does not report the federal loan debt students carried when they finished."`                                                                                             |
| 954-955 | inline                         | `"Students who finished here carried a median of ... in federal loans. The source publishes no year for this figure."`                                                                 |

The provenance block, in full:

```
CostReportPage.kt:963-984
private fun FlowContent.sourcesSection() {
  section("report-sources") {
    h2 { +"Sources and what this is not" }
    p { +("The cost and price figures come from the ${CostSources.SCORECARD_ATTRIBUTION}. The merit figures " +
          "come from each school's own Common Data Set, cited beside them.") }
    p { +("These are averages for past students at each school. They are not an offer, and no figure here is a " +
          "price this family has been quoted.") }
    p { +("Only a school's own financial aid offer is a price for this family. Loans and work-study are never " +
          "subtracted from any price here.") }
  }
}
```

and the per-school CDS citation line:

```
CostReportPage.kt:932   p("report-source") { +"Source: ${merit.source.citedAs}." }
```

**What breaks with a 5th source:** `sourcesSection()` is a **hard-coded
two-publisher sentence with no input at all** — it takes no parameter, reads no
profile, and is called unconditionally (`CostReportPage.kt:259`). It says
"Scorecard" for prices and "Common Data Set" for merit. It is already wrong for
IPEDS SFA / IPEDS IC_AY prices. A 5th publisher requires editing this literal by
hand, and there is nothing in the type system that would notice if nobody did.
This is the single most concrete instance of the brief's criterion 1 ("adding a
fifth publisher must not require anyone to write a new hedge sentence by hand")
failing today.

Counter-example worth noting: `withheldBlankFor` (`:120-123`) does it right —
the page supplies only the frame and the pointer, and imports the _reason's
words_ from the domain (`WithheldReason.cellPhrase`). That is C5 in miniature,
working.

---

## 3. iOS (`ios-app/`, Swift)

**There is no provenance or hedge copy in the iOS app. None.** Stated plainly
because it changes the shape of the answer.

How I counted: `find ios-app -name '*.swift' -not -path '*/build/*' | wc -l` →
**124** Swift files. Then
`grep -rn -iE "scorecard|common data set|ipeds|self-report|provenance|unaudited|department of education" ios-app/ --include=*.swift`
→ **4 hits outside vendored `build/DerivedData`**, and none of them is copy:

```
ios-app/UnicoachiOS/ResidencyStates.swift:12   /// The two-letter USPS code, as College Scorecard's `STABBR` spells it.
ios-app/UnicoachiOS/ResidencyStates.swift:47   /// contract: the server's vocabulary is the wider College Scorecard `STABBR`
ios-app/UnicoachiOS/CollegeEntryDetailView.swift:92  /// Read-only provenance (RFC 91/136): the quotes that put this college on
ios-app/UnicoachiOS/CollegeListModels.swift:27  /// the screen displays provenance and never writes it.
```

All four are **doc comments**, and the two `provenance` ones mean
_conversational_ provenance (which chat turn added a college — RFC 91/136), not
data provenance.
`grep -rn -iE "not reported|we hold|published price|estimate|approximate" ios-app/UnicoachiOS --include=*.swift`
→ 1 hit, a comment in `Markdown/MarkdownView.swift:310`. iOS test sources
(`ios-app/UnicoachiOSTests/`, 40 files) contain **zero** hedge assertions.

**Why:** the app renders the coach's markdown reply as an opaque string —
`ConversationView.swift:296  let source = turn.coachMessage?.content ?? turn.coachStreamingText`
then `:302  MarkdownView(source: source)`. The iOS surface therefore **inherits
whatever hedge the coach speaks and adds none of its own**. The brief's claim
that "the iOS surfaces write theirs" is **not true of the live tree** — I found
no iOS-authored hedge sentence. That is good news for the bet: iOS is a surface
that already gets the hedge for free, so a derived hedge lands there with zero
Swift changes.

---

## 4. The ONE natural seam per surface

| surface               | the seam                                                                                                                                                                                                                                                                             | file:line                                            | honest?                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **coach prompt**      | **no code seam exists.** The prompt is a DB row, seeded by an immutable insert-only migration: `db/schema/0088.seed-coach-system-prompt-v20.sql`, pinned by `(name, version)` in `service/src/main/resources/service.conf`. Changing the hedge means writing v21 as a new migration. | `db/schema/0088.seed-coach-system-prompt-v20.sql`    | **No seam.** The nearest _code_ seam is `CollegeCostChatTool.DESCRIPTION` (`CollegeCostChatTool.kt:1447`), the tool-description string that reaches the model in the same context window and is already assembled from Kotlin constants (`FigureStatus.*.value`, `AT_HOME_ASSUMPTION_STATEMENT` at `:1549`). A derived hedge _can_ be interpolated there without a migration.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **chat tools / wire** | **`FigureStatusCopy.statementOf(status: FigureStatus): String?`**                                                                                                                                                                                                                    | `service/.../costs/canonical/FigureStatusCopy.kt:47` | **Yes — this is the real seam.** It is the single function that turns a store-side status code into a spoken sentence; it already returns publisher-neutral prose; every `when` in the file is exhaustive with no `else` by deliberate design (`:36-39`), so widening its input signature forces every arm to be re-decided at compile time. Its output funnels through exactly one wire renderer, `CollegeCostChatTool.figureStatusObject(note)` (`:651`), which emits `field` + `status` + `statement`. Widen the signature to `statementOf(status, source)` (or `statementOf(figure)`) and **both** the coach wire and the report page inherit it, because the page reads the same `note.statement` (`CostReportPage.kt:665`). The one thing to fix first: `REPORTED -> null` (`:50`) — the arm where a self-report and an administrative record are indistinguishable — must become a sentence-or-null decision that has the source in hand. |
| **cost report page**  | **`blankFor(cost, field, otherwise): String`**                                                                                                                                                                                                                                       | `public-web/.../render/CostReportPage.kt:645-666`    | **Yes, for the blanks.** Every "why is there no number" cell on the page routes through this one function, which reads `cost.statusNoteFor(field)` and returns `note.statement` for five of the six statuses. But it only covers **absences**. For a hedge on a figure that IS shown — the C2/C6 case — the seam is one level up: **`figureOf(amountUsd, blank, note): SchoolFigure`** (`:770-774`), the file's own comment calls it _"The ONE site that turns 'an amount that may be absent, and why' into the case it is"_, and `SchoolFigure.Amount` already carries `val note: String? = null` (`:760`) rendered at `:901` as `span("report-note")`. **That `note` slot is an existing, unused-for-provenance hook for a per-figure hedge.** What has **no** seam is `sourcesSection()` (`:963`) — it takes no arguments and can only be edited by hand.                                                                                     |
| **iOS**               | **no hedge seam is needed, and none exists.**                                                                                                                                                                                                                                        | `ios-app/UnicoachiOS/ConversationView.swift:296,302` | **Honest answer: the surface has no hedge of its own.** The nearest thing to a seam is `MarkdownView(source:)` at `ConversationView.swift:302`, which renders the coach's reply verbatim. iOS is a _pass-through_: any hedge the coach or the wire derives reaches the app with no Swift change. If a future native cost screen is built (none exists — no Swift file reads `figure_statuses`, `data_availability`, or `withheld_figures`; grepped, zero hits) it would need its own seam, and today it would have to retype the copy.                                                                                                                                                                                                                                                                                                                                                                                                           |

**Summary of the seam picture:** three of four surfaces already funnel through
either `FigureStatusCopy` or something that reads its output. The renderer half
of C5 is therefore _cheap_ — one function signature. What is missing is not a
seam; it is the **input**: `MoneySource` does not reach any of these three
functions, because it does not reach `service/src/main/kotlin` at all (§0). Any
of C1/C2/C3 is, at the code level, mostly "carry `source` from
`CanonicalMoneyReadRows` through `CollegeFigures`/`CollegeCost` into
`FigureStatusNote`".

---

## 5. Tests that pin hedge WORDS

These are the tests a copy change breaks. Grepped with a 17-alternative literal
regex over `--include=*.kt --include=*.swift`, filtered to `src/test` and
`Tests/`, worktrees and `build/` excluded. **26 assertions across 7 files.**
(iOS: **0**. db module: **0** — the db tests assert codes, never copy.)

### `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` (12)

| line          | pinned string                                                                                                                           |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| 176           | `"Not reported by this school"`                                                                                                         |
| 213           | `"Not reported by this school"`                                                                                                         |
| 251           | `"$0<span class=\"report-note\">our assumption, not a figure this school published</span>"` (**pins the HTML too**)                     |
| 322           | `"Riverside State University's 2024-25 Common Data Set"` (pins `CdsCitation.citedAs` shape)                                             |
| 329           | `"U.S. Department of Education College Scorecard"`                                                                                      |
| 330           | `"Common Data Set"`                                                                                                                     |
| 358           | `"The source publishes no year for this figure"`                                                                                        |
| 375           | `"Not shown \u2014 the state the family lives in is not on file"`                                                                       |
| 407           | `"<span class=\"report-blank\">Not reported by this school</span>"`                                                                     |
| 431           | `"Not reported by this school"`                                                                                                         |
| 515, 548, 563 | `"this school publishes this figure for in-state students"` / `"... in-state students. Your family"` (pins `WithheldReason.cellPhrase`) |
| 527           | `"Not reported by this school"`                                                                                                         |
| 739           | `"This school does not report the federal loan debt"`                                                                                   |
| 460           | `assertFalse(body.contains("Ashford College's 2024-25 Common Data Set"))` — a **negative** pin                                          |

(`public-web/src/test/kotlin/ed/unicoach/web/FakeCostReportSource.kt:216`
mentions the string in a comment only, not an assertion.)

### `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt` (7)

| line | pinned string                                                                                 |
| ---- | --------------------------------------------------------------------------------------------- |
| 200  | `assertEquals("This school did not report this figure.", books.statement)`                    |
| 208  | `assertEquals("This figure is withheld by the publisher for privacy.", suppressed.statement)` |
| 216  | `assertEquals("We have not collected this figure yet.", ours.statement)`                      |
| 248  | `assertEquals("This figure is withheld by the publisher for privacy.", note.statement)`       |
| 304  | `"This is the publisher's own estimate for this school, not a figure the school reported."`   |
| 1221 | `assertEquals("We have not collected this figure yet.", note.statement)`                      |
| 1335 | `assertEquals("This figure is withheld by the publisher for privacy.", suppressed.statement)` |
| 1352 | `assertEquals("This school did not report this figure.", schools.statement)`                  |
| 2805 | `basis.statement.contains("No Dorms U: no residence halls")`                                  |

These are `assertEquals` on the **exact** `FigureStatusCopy` sentence — the
tightest coupling in the tree. Any reword of a status sentence fails 8
assertions here.

### `service/src/test/kotlin/ed/unicoach/coaching/costs/canonical/CollegeFiguresTest.kt` (1)

| 642 |
`"This is the publisher's own estimate for this school, not a figure the school reported."`
|

### `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt` (7)

| line | pinned string                                                                                                           |
| ---- | ----------------------------------------------------------------------------------------------------------------------- |
| 574  | `assertEquals("U.S. Department of Education College Scorecard", result.getValue("source")...)`                          |
| 983  | `"Need Met U's 2024-25 Common Data Set"`                                                                                |
| 1044 | `note.contains("is not listed in that filing")` (pins `AidPolicyWire.FORMS_NOTE`)                                       |
| 1098 | `note.contains("we could not read")` (pins `FORMS_NOT_COLLECTED_NOTE`)                                                  |
| 1159 | `CollegeCostChatTool.AID_POLICY_NO_FILING` (**by constant** — reword-safe)                                              |
| 1167 | `AID_POLICY_NO_FACT_IN_FILING.contains("We hold this school's Common Data Set filing")` (by constant AND by literal)    |
| 1226 | `assertFalse(source.contains("Common Data Set"))` — negative pin: the Scorecard `source` string must not absorb the CDS |
| 1233 | `assertEquals("Cited Merit U's 2024-25 Common Data Set", citation.getValue("cited_as")...)`                             |
| 2298 | `description.contains("has no residence halls")`                                                                        |

### `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` (6)

Pins prompt copy read out of the seeded `system_prompts` row:

| line          | pinned string                                                                         |
| ------------- | ------------------------------------------------------------------------------------- |
| 283           | `appended.contains("U.S. Department of Education College Scorecard")`                 |
| 345           | `moneyParagraph.contains("U.S. Department of Education College Scorecard")`           |
| 462           | same                                                                                  |
| 500           | `appended.contains("Common Data Set")`                                                |
| 611, 730, 828 | `"no residence halls"` / `"no residence halls has none"` / `"say the reason plainly"` |
| 1402          | `appended.contains("not listed in that filing")`                                      |

Because `system_prompts` is insert-only/immutable, these assertions are pinned
against a **migration**, not against a Kotlin constant. Changing the hedge in
the prompt requires a new seed migration _and_ editing these lines.

### `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt` (3)

| line | pinned string                                                                                  |
| ---- | ---------------------------------------------------------------------------------------------- |
| 1218 | `"netPriceNote=[This figure is withheld by the publisher for privacy.]"`                       |
| 1319 | `assertFalse(call2Text.contains("We have not collected this figure yet."))` — negative pin     |
| 1344 | `"netPriceStatus=[not_collected_by_us] netPriceNote=[We have not collected this figure yet.]"` |

### `service/src/test/kotlin/ed/unicoach/coaching/admissions/*` (5)

`CollegeAdmissionsServiceTest.kt:361, 377, 379, 400` and
`CollegeAdmissionsChatToolTest.kt:113, 511, 535, 543` all pin the
`"<College>'s <YYYY-YY> Common Data Set"` `citedAs` shape.

**Blast-radius conclusion for a copy change:** the six `FigureStatusCopy`
sentences are pinned by `assertEquals` at 9 sites in 2 files; the `citedAs`
format is pinned at 9 sites in 4 files; `"Not reported by this school"` at 5
sites in 1 file (all reading the `internal` constant's _value_, not the constant
— `CostReportPage.kt:92-94` says the tests assert on the constant, but the
grepped lines are string literals, so a reword breaks them). A C5 slice that
only _adds_ a source-aware clause to `REPORTED` (today `null`) breaks **none of
these** — because no test asserts anything about a `REPORTED` figure's sentence.
That is the cheapest possible first slice.
