# RFC 177 — One hedge seam: attribution derived per figure, not typed per site

Slice: `soft/01/one-hedge-seam` (brief 0008, gate 2 approved 2026-09-05). Lane
A.

## Summary

unicoach tells families that an IPEDS figure came from the College Scorecard.
`CostSources.SCORECARD_ATTRIBUTION` (`SingleSchoolBasis.kt:225`) says every cost
figure comes from "the U.S. Department of Education College Scorecard", and the
coach prompt says the same in prose (`db/schema/0092:117-120`). Both contradict
`CanonicalMoneyLoader.ORDERED_SOURCES` (`:1120`), which ranks `IPEDS_SFA`,
`IPEDS_IC_AY`, `SCORECARD` — so the publisher we name is, for most figures, not
the publisher that won.

The cause is structural, not a typo. The winning `MoneySource` is read out of
the store (`CanonicalMoneyReadRows.kt:28,48`), survives into `CollegeFigures`,
and is then **dropped at two lines** (`CollegeFigures.kt:371`, `:426-430`).
`MoneySource` appears in **zero** files under `service/src/main/**` or
`public-web/src/main/**`. Nothing above the domain layer can know who published
a number, so every surface that wanted to say it had to type it by hand, and the
hand-typed answer drifted off the data.

This RFC threads the winning source through to the copy seam and derives the
attribution there. One object decides how a money figure is described; chat, the
report page and (as pass-through markdown) iOS inherit it. The hand-written
publisher constant is deleted.

No new table, no column. One seed migration for coach prompt **v23**.

## Decisions

**D1. The spoken label is service-side, not on `MoneySource`.** `MoneySource`
(`db/.../models/MoneySource.kt`) is external identity — a code list, explicitly
"NOT a vocabulary table" (brief 0008). English that a family reads is a
rendering concern, and it belongs beside the other rendering decision. A new
`MoneySourceCopy` object lives next to `FigureStatusCopy` in
`service/.../coaching/costs/canonical/`, with one exhaustive `when` and **no
`else`**, so a fifth `MoneySource` member fails to compile at the seam.

**D2. The six shipping sentences keep their exact current return values, and the
widened form is a second, differently NAMED function.** This is not politeness
to callers, it is a hard constraint: `SystemPromptCatalogTest:1813-1821` walks
`FigureStatus.entries` and asserts the **immutable v19 prompt body** contains
that sentence for each. Rewording any of the six fails against a committed row
that a new seed cannot fix. So the frozen form stays byte-identical and is
RENAMED to `agentlessStatementOf(status): String?`, while
`statementOf(status, source)` carries the publisher. The rename is the point:
two overloads differing only in arity let a caller holding a `MoneySource` drop
the argument and silently ship the agentless "the publisher" hedge — the exact
defect this slice removes — and it would compile, format and pass. Told apart by
name, that misuse cannot be typed by accident.

**D3. The publisher is named exactly where the sentence is about the publisher's
act.** The two-argument arms:

| Status                        | Two-arg sentence                                                                |
| ----------------------------- | ------------------------------------------------------------------------------- |
| `REPORTED`                    | "This figure comes from <label>." — the arm that is `null` today                |
| `IMPUTED_BY_PUBLISHER`        | "<Label> estimated this for the school; the school did not report it."          |
| `SUPPRESSED_BY_PUBLISHER`     | "<Label> withholds this figure to protect students' privacy."                   |
| `NOT_REPORTED_BY_INSTITUTION` | unchanged one-arg text — the school's silence, not the publisher's act          |
| `NOT_APPLICABLE`              | unchanged one-arg text                                                          |
| `NOT_COLLECTED_BY_US`         | unchanged one-arg text — ours; naming a publisher here would misattribute a gap |

The routing rule is `FigureGapOwner`, which already exists and already says
whose act each status is. This is the anti-vacuity bar met by construction:
three arms differ, three deliberately do not, and the reason each is on its side
is stated.

**D4. `FigureStatusNote` gains the winning source.** The note is the existing
carrier of "a code and its sentence travelling together"
(`CollegeCostService.kt:698-737`). It gains `source: MoneySource`, NON-NULL:
both construction sites read the publisher off a row that exists, and a cell no
loader ever wrote produces no note at all. It also goes ON THE WIRE: each
`figure_statuses` entry carries `source` (the `MoneySource` slug) beside
`statement`, the `income_band` + `income_band_label` convention (RFC 151 D-D)
applied again — `imputed_by_publisher` and `suppressed_by_publisher` are acts of
a publisher, and a reader must not have to parse the English sentence to learn
which publisher acted. The top-level source phrase stays PROSE with no parallel
code array beside it: it is what the coach reads aloud, and one figure's
publisher is a different question from the page's source list. The same holds
one layer down — `DatedFigure`, `DatedStat` and `FigureProvenance` each carry a
non-null source, because each IS a row and `price_figures.source` /
`cohort_money_stats.source` are required columns. "No publisher" is the ABSENCE
of the carrier, never a carrier holding null; a fabricated publisher would be
exactly the lie this RFC removes.

**D5. `sourcesSection()` takes what it describes.** It is a zero-argument
constant emitter (`CostReportPage.kt:1016-1042`) with one caller (`:261`). It
takes the distinct winning `MoneySource`s of the figures actually on the page
and names those, in `ORDERED_SOURCES` order. A page with no IPEDS figure stops
claiming an IPEDS source.

The served set is **value-bearing**, not row-bearing
(`CollegeFigures.servedSourcesOf` -> `servedFigureSourceOf`). A publisher enters
it only when a figure of its own, WITH A NUMBER IN IT, is shown at the served
year and band. A suppressed, not-applicable or not-reported cell is a row and
not a figure, and a value-free row at another year — the fall-through `statusOf`
needs so a suppression one year over is still spoken — is not even at this year.
The sentence "the cost, price and federal debt figures come from ..." is a claim
about figures the family can SEE, so naming a publisher of none of them is the
same false attribution this RFC deletes, one size smaller. Such a row still
speaks in its OWN sentence, which names the publisher that withheld the cell and
is right to: whose act it was is exactly the fact there.

The page emits the sentence or omits it; the nullable `spokenListOf` result
travels to the point of use and is never restored to `""`
(`moneyAttributionSentence`, which also keeps sentence composition out of the
section's rendering).

The same rule governs the chat payload's `source` key: it names the distinct
winning sources of the figures that payload actually carries, and it is
**absent** when there are none. A payload or a page with no money figure on it
has no publisher to name, and emitting an empty value there would be a claim of
its own.

`ORDERED_SOURCES` ranks only the three sources that compete for one natural key,
so the spoken order is stated exhaustively as `MoneySourceCopy.rankOf`, where
`COMMON_DATA_SET` — filled elsewhere, in competition with nobody — also gets a
rank. The two are TIED rather than merely documented: `ORDERED_SOURCES` is
widened from `internal` to public and `MoneySourceCopyTest` asserts the spoken
order against the loader's own list, so a re-rank in `:college` fails a test
instead of silently changing what every page and payload says. A hand-typed
literal restating the same three members would have agreed with `rankOf` forever
and caught nothing.

`spokenListOf` returns `String?` — `null`, never `""` — so absence has ONE
representation and no caller can splice a hole into family-facing copy. The
grammar itself is `common/util/Phrase.kt`'s `phraseOf`, not a second copy of the
comma-and-"and" rule; this object owns the vocabulary and the order and nothing
else. The profile-level roll-up of publishers is one derived member on
`CollegeCostProfile`, read by both surfaces, rather than the same `flatMap`
written at each.

**D6. Coach prompt v23 rewrites exactly the two money publisher-naming
sentences** (`0092:117-120`, `:325-328`) to "attribute each figure to the source
the tool names beside it" — the agnostic form the same prompt already uses at
`0092:348`. It is an **interior edit**, so it follows the `0086`/v19 migration
shape, not the additive one, and the test proves byte-reconstruction: v23 with
the edited spans removed equals v22. Rollback
`COACHING_SYSTEM_PROMPT_VERSION=v22`.

**D7. The grep test is scoped to money-figure attribution.** The slice text asks
that no hard-coded publisher name remain in `service/src/main/**` or
`public-web/src/main/**`. Taken literally that is 122 hits, ~75 of them doc
comments, plus true statements about non-money corpora (Common Data Set
citations, Federal Student Aid form names). The test therefore asserts the thing
the slice is about: **no literal publisher name appears in any string that
attributes a money figure** — enforced as a grep over the two module trees
`service/src/main` and `public-web/src/main` WHOLE (never a package inside them,
which goes stale the moment a file moves), with the banned names DERIVED from
`MoneySourceCopy.labelOf` over `MoneySource.entries` plus the short aliases no
label spells, plus the compile-time exhaustiveness of D1. Doc comments and
non-money corpus names are out of scope and are left alone.

A live line that names a publisher for something that is NOT a money figure —
the IPEDS on-campus housing flag, an operator log line about the IPEDS/Scorecard
disagreement — leaves the ban ONE way: a `// money-attribution-exempt: <reason>`
comment on that line. The marker is purpose-built and written where the exempted
code is, so the line states its own exemption and its own reason; a list of
incidental fragments held in the test file instead (a constant's name, a log
field) left the live string looking unpoliced, let any future money string that
happened to mention one of them self-exempt, and re-armed or widened the ban on
a rename. The exemption is per LINE, never per file. When the sweep fires it
prints `path:line: <text>`, and the "it can fire at all" guard names its
file-count floor (`MIN_SCANNED_FILES`). See "Out of scope".

**D8. Nothing else in this RFC is a tier.** Assurance tiers are
`soft/02/assurance-tiers` and land behind this seam. This RFC only makes the
seam able to see the source.

## Out of scope

- **The four aid-policy constants** named in the slice text
  (`AidPolicyWire.FORMS_NOTE`, `FORMS_NOT_COLLECTED_NOTE`,
  `AID_POLICY_NO_FILING`, `AID_POLICY_NO_FACT_IN_FILING`). Research shows they
  are statements about **corpus coverage** — whether we hold a filing — not
  about a figure's publisher. Deriving them from `MoneySource` would say
  something false. Reported to /chart as a spec defect rather than built.
- **`CostReportPage.kt:1023`'s "each school's own Common Data Set"** for the
  merit and borrowing half of the same paragraph. It is true and it is not a
  money-figure attribution. D5 replaces the price sentence in that paragraph and
  leaves this one.
- The assurance tier, per-document assurance, and any coherence finding.

## Detailed Design

### The seam

```kotlin
object MoneySourceCopy {
  /** How a family hears this publisher named. The only English name of a publisher. */
  fun labelOf(source: MoneySource): String =
    when (source) {
      MoneySource.IPEDS_IC_AY -> "the U.S. Department of Education's IPEDS survey of college costs"
      MoneySource.IPEDS_SFA -> "the U.S. Department of Education's IPEDS survey of student financial aid"
      MoneySource.SCORECARD -> "the U.S. Department of Education College Scorecard"
      MoneySource.COMMON_DATA_SET -> "the school's own Common Data Set"
    }
}

object FigureStatusCopy {
  fun agentlessStatementOf(status: FigureStatus): String? = /* UNCHANGED VALUES, byte-frozen, renamed (D2) */

  fun statementOf(status: FigureStatus, source: MoneySource): String? =
    when (status) { /* exhaustive, no else — D3 */ }
}
```

The three arms that name nobody delegate to `agentlessStatementOf`, which is
also what a caller with no row at all says (`FitLensService`'s
`NOT_COLLECTED_BY_US` digest). There is no null `MoneySource` on this seam: a
canonical money row always carries its publisher, so the choice is between
having a figure and not having one.

### Threading

The source is dropped at two lines and both have the winning row in scope:

- `CollegeFigures.kt:368-371` — `pricesByAddress` keys down to
  `FigureReading<Int>`; it keeps `source`.
- `CollegeFigures.kt:426-430` — `DatedStat(vintage, residencyScope, reading)`
  ignores `row.source`; `DatedStat` gains it.

`DatedFigure` (`:301-309`) and `DatedStat` (`:326-334`) gain
`source: MoneySource` (non-null, per D4); `CollegeFigures.statusOf` (`:545-563`)
and `ServedFigures.statusOf` (`:80-83`) return the status **and** the source (a
small `FigureProvenance` pair rather than a widened tuple at eight call sites);
`FigureStatusNote` (`CollegeCostService.kt:698-737`) gains the field per D4.

`MoneySourceCopy` also owns `openingLabelOf` (the label at the start of a
sentence, so no caller hand-capitalises a publisher's name), `rankOf` (above)
and `spokenListOf` (the distinct publishers of a set of figures, joined by
`phraseOf` into one English list, `null` for none).
`CollegeFigures.servedSourcesOf`, `CollegeCost.moneySources` and
`CollegeCostProfile.moneySources` carry that set to the two renderers, and
`CollegeCostChatTool.SOURCE_KEY` names the payload key that
`SCORECARD_ATTRIBUTION`'s alias used to fill.

None of the three new `source` fields carries a default and none is nullable: a
default is how the publisher was dropped in the first place, so every
construction site states the source it read off its own row.

### The two renderers

- **Chat.** `CollegeCostChatTool.figureStatusObject` (`:742-754`) emits the
  derived sentence. The top-level
  `put("source", CostSources.SCORECARD_ATTRIBUTION)` (`:89`) becomes the
  distinct winning sources of the payload's own figures. The tool description
  (`:1645`) stops naming the Scorecard and says the payload names its own
  source.
- **Report page.** `CostReportPage.blankFor` (`:647-669`) already reads
  `note.statement`, so it inherits the derived sentence with no change beyond
  the note's new field. `sourcesSection()` takes the page's distinct sources
  (D5). Its sentence says "The cost, price and federal debt figures come from
  ...", because `servedSourcesOf` walks every `CostField` and
  `MEDIAN_DEBT_AT_COMPLETION_USD` is one of them: said as "cost and price" it
  would name a publisher of only the debt figure as a publisher of this school's
  prices.

### The prompt

`db/schema/0093.seed-coach-system-prompt-v23.sql`, interior-edit shape after
`0086`: full v22 body, two spans replaced. `service.conf:133` becomes `"v23"`,
with the cumulative rollback couplet appended after `:132`.
`CoachingConfigTest.kt:17` moves to `v23`.

## Files Modified

| File                                              | Change                                                                           |
| ------------------------------------------------- | -------------------------------------------------------------------------------- |
| `service/.../costs/canonical/MoneySourceCopy.kt`  | NEW — the only English publisher names                                           |
| `service/.../costs/canonical/FigureStatusCopy.kt` | add `statementOf(status, source)`; rename the frozen form `agentlessStatementOf` |
| `service/.../costs/SingleSchoolBasis.kt`          | DELETE `SCORECARD_ATTRIBUTION`                                                   |
| `service/.../costs/CollegeCostChatTool.kt`        | delete alias `:1216`; derive `:89`; reword description `:1645`                   |
| `service/.../costs/CollegeCostService.kt`         | `FigureStatusNote.source`; pass source at `:1888`, `:1433`, `:1953`              |
| `service/.../costs/canonical/CollegeFigures.kt`   | keep `source` at `:371` and `:426-430`; carriers                                 |
| `service/.../costs/canonical/ServedFigures.kt`    | `statusOf` returns provenance                                                    |
| `service/.../coaching/costs/BorrowingWire.kt`     | `gapLabel` uses `CdsCitation.citedAs` instead of its hand-written name           |
| `service/.../coaching/fitlens/FitLensService.kt`  | pass source at `:846`                                                            |
| `public-web/.../render/CostReportPage.kt`         | `sourcesSection(sources)`; caller at `:261`                                      |
| `db/schema/0093.seed-coach-system-prompt-v23.sql` | NEW — prompt v23                                                                 |
| `service/src/main/resources/service.conf`         | `v23` + rollback couplet                                                         |
| tests                                             | below                                                                            |

## Implementation Plan

1. `MoneySourceCopy` + the two-arg `statementOf`, with unit tests. No callers
   yet.
2. Thread the source: `CollegeFigures` carriers, `statusOf`, `FigureStatusNote`.
3. Switch the two renderers; delete `SCORECARD_ATTRIBUTION` and its alias.
4. `sourcesSection(sources)` and its caller.
5. Prompt v23 migration, `service.conf`, `CoachingConfigTest`.
6. Full `nix develop -c bin/test`; report executed counts and every pinned
   assertion that moved, with the reason.

## Tests

- **The live defect, pinned.** A college where `ORDERED_SOURCES` picks IPEDS is
  never attributed to the Scorecard — asserted on the chat payload and on the
  report page. Fails on today's code.
- **The `REPORTED` arm gains coverage** (untested today): each of the four
  sources produces its own attribution sentence. Note where that sentence is and
  is not reachable: `figureStatusesOf` already drops the note for a shown
  non-imputed figure, so on the wire and on the page a plainly reported figure
  is attributed by the payload's own `source` (D5), not by this sentence. The
  arm is covered by unit tests and is the seam `soft/02` will read.
- **Anti-vacuity:** the three publisher-naming arms produce three different
  sentences on real rows, and the three unchanged arms are asserted equal to the
  agentless text.
- **Exhaustiveness is compile-time**, plus a test that walks
  `MoneySource.entries` and asserts a non-blank distinct label for each.
- **D2 regression guard:** a test pins `agentlessStatementOf`'s output for all
  six statuses as literals typed beside the code, so a reword fails locally and
  first. The coupling itself — the immutable v19 prompt body reciting those same
  six sentences — is asserted where the row can be read, in
  `SystemPromptCatalogTest`'s existing enum walk.
- **Prompt v23:** `assertFalse(v23.startsWith(v22))`, one `insertedSpan` per
  edited span with both boundaries, and byte-reconstruction by **replacement**:
  each new span put back to its v22 wording must equal v22 exactly. Removal —
  the form `0086`/v19 could use — only proves an insertion, and RFC 177's two
  spans are replacements. v22 stays selectable.
- **The grep test (D7):** no money-attribution string in `service/src/main/**`
  or `public-web/src/main/**` contains a literal publisher name — both trees
  walked whole, with the banned names derived from `MoneySourceCopy.labelOf`,
  and a companion test that the sweep reads both trees and CAN fire.
- **Precedence agreement (D5):** `MoneySourceCopyTest` asserts the spoken order
  against `CanonicalMoneyLoader.ORDERED_SOURCES` itself, so a re-rank in
  `:college` fails here.
- `nix develop -c bin/test` full suite; executed counts reported.
