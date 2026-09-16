# RFC 227 — One date seam, and the format is US-centric

**Slice:** `test/04.5/one-date-seam` (brief 0010, "when do I test?")
**Carries:** D21 — "Dates are tricky... we should have a single place where we
format dates. And we should start with a US-centric format e.g. Sep 12, 2026."
**Lane:** A (rfc). Behaviour-bearing: user-visible copy changes on three
surfaces and one coach-prompt rule is added.

## Problem

A date's shape is currently a property of whichever file happens to print it.
Measured on `main@9870374e`, not recalled — five main-source sites format a
date, in four shapes, and two more print a raw ISO date into English prose:

| Where                                                       | Today                                    | Audience         |
| ----------------------------------------------------------- | ---------------------------------------- | ---------------- |
| `CoachingService.monthName` + `renderGraduationPhrase`      | `on 15 June 2028`                        | the coach, aloud |
| `CdsMonthDay.phrase` (`:db`)                                | `November 1`, no year                    | the coach, aloud |
| `admin-web` `CellRender.DATE_FORMAT`                        | `Jan 3, 2026`                            | an operator      |
| `IpedsLoader.CLOSED_AT_FORMAT`                              | `MM/dd/uuuu`                             | **a parser**     |
| `CoachClock.COACH_ZONE_LABEL`                               | a ZONE name, not a date                  | the coach, aloud |
| `AdmissionTestTimelineChatTool.whyOf` / `resolutionBasisOf` | `registration closed on 2026-09-18`      | the coach, aloud |
| `public-web` `ExplainerPage`                                | `As of 2026-09-14`, `Fetched 2026-09-14` | any visitor      |

**Seven date-shaped sites, not the four D21 names.** D21 was written from a
four-site survey (`CoachingService.monthName`, `CollegeAdmissionsChatTool`,
`admin-web` `CellRender.DATE_FORMAT`, `IpedsLoader.CLOSED_AT_FORMAT`). The
measurement above found seven, and **two of the missing ones are user-facing**:
the timeline tool's English sentences, and `public-web`'s `As of` / `Fetched`
lines. Neither carries a formatter, so a grep for `DateTimeFormatter` misses
both -- they interpolate a `LocalDate` straight into a sentence. The record says
seven here because that is what was there.

`admin-web` is already `MMM d, yyyy`, so the target format is a precedent in
this tree and not an invention. `IpedsLoader` is a PARSER and is not in scope at
any point in this RFC; `COACH_ZONE_LABEL` names a timezone, not a date.

**On "fails the build".** The slice's acceptance criterion says a new
hand-rolled month name or date pattern must fail the build. Honestly stated,
what this RFC delivers is that it **fails the test suite, and therefore the
commit gate**. No Kotlin compiler can refuse a hand-written date pattern, and
naming the weaker bar is better than quietly meeting it.

## What this RFC does NOT do

It adds **no table, no column and no migration**. The slice is a rendering
consolidation. There is no DDL to approve.

It does not change what any date MEANS. Three distinctions are load-bearing and
each survives by construction, not by care:

1. **A year-less Common Data Set cycle stays year-less.** The seam's month/day
   entry point takes a `Month` and an optional day and has **no year
   parameter**, so no caller can make it invent one (RFC 148's rule, kept by RFC
   197).
2. **"not recorded" (our gap) stays distinct from "not reported" (the source's
   silence).** The seam returns a DATE AND NOTHING ELSE — no preposition, no
   caveat, no provenance. Those words stay with the caller that owns the claim.
3. **A date we derived stays labelled as ours.** `resolutionBasisOf` keeps every
   word of RFC 197 D4; only the shape of the date inside it changes.

And RFC 209's rule holds: a year-only `PartialDate` has no month to leak. The
seam's year entry point returns a year and cannot render a month.

## Detailed Design

### The seam

A new file `common/src/main/kotlin/ed/unicoach/common/util/UsDate.kt`, in the
`:common` module — which depends on nothing and which every other module already
depends on. It is the exact precedent of `Phrase.kt` beside it: grammar, not
vocabulary.

```kotlin
package ed.unicoach.common.util

/**
 * Every date a human reads, in one shape: `Sep 12, 2026` (brief 0010 D21).
 *
 * It returns a DATE AND NOTHING ELSE -- no preposition, no caveat, no
 * provenance. "the day is not recorded" (our gap), "day not reported" (the
 * source's silence) and "this year is ours, worked out from the graduation
 * date" are CLAIMS, and they stay with the caller that can justify them. A
 * single wording here would merge three different statements into one.
 *
 * Precision is carried by the TYPE, not by a nullable field, so a caller cannot
 * render more than it holds: there is no year parameter on [monthDay], and
 * [of] over a [Year] has no month to leak (RFC 209).
 */
object UsDate {
  /** `Sep 12, 2026`. */
  fun of(date: LocalDate): String

  /** `Sep 2026` -- the day is not known to the caller, and none is invented. */
  fun of(month: YearMonth): String

  /** `2026` -- only the year is known. */
  fun of(year: Year): String

  /**
   * `Nov 1`, or `Nov` when the day is absent. NO YEAR, ever: this entry point
   * exists for a source that publishes a month and a day and no year (CDS), and
   * it has no parameter that could carry one.
   */
  fun monthDay(month: Month, day: Int?): String
}
```

Implementation: one `DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)` and
its three shorter siblings, all private to this file. `Locale.US` and not
`Locale.ENGLISH`: the decision is "US-centric", and the locale is the place that
says so.

### Adopting it

- **`:db` — `CdsMonthDay.phrase`** delegates its month name to
  `UsDate.monthDay(Month.of(month), day)` and keeps its own two words: `Nov 1`,
  and `Nov, day not reported` for the month-only case.
  `CollegeAdmissionsChatTool.datePhrase` is already a one-line delegate to this
  and is untouched.
- **`:db` — a new `PartialDate.usDate()` extension**, one `when` over the sealed
  type, mapping each variant to the matching `UsDate.of` overload. The compiler
  is what refuses a fourth precision.
- **`:service` — `CoachingService.renderGraduationPhrase`** calls that
  extension. `monthName` is DELETED. The three sentences become
  `on Jun 15, 2028`, `in Jun 2028 (the day is not recorded)`, and
  `in 2028 (only the year is recorded — no month, no day)` — the caveats are
  unchanged, only the shape moves.
- **`:service` — `renderDateBlock`'s "Today's date is ..."** moves from ISO to
  `UsDate.of`. This is deliberate and is decision 2 below: a prompt that TELLS
  the model to say `Sep 12, 2026` and then shows it `2026-09-12` two lines above
  is the self-contradicting block RFC 187's review caught. One shape in the
  prompt prose.
- **`:service` — `AdmissionTestTimelineChatTool`'s English**: `whyOf` (three
  arms), `resolutionBasisOf`, the school-day `whyOf` arms that print `checkedOn`
  and `expiresOn`. **Every JSON wire value stays ISO** — `as_of`, `expires_on`,
  `test_date`, `registration_deadline`, `fee_waiver_by`, `score_release_date`
  are unchanged, because they are machine fields and RFC 197 chose ISO for them
  on purpose. Only prose changes.
- **`:public-web` — `ExplainerPage`**: `As of Sep 14, 2026` and
  `Fetched Sep 14, 2026`.
- **`:admin-web` — `CellRender`**: `DATE_FORMAT` is deleted and the call sites
  use the seam. The rendered bytes are identical (`MMM d, yyyy` in
  `Locale.ENGLISH` and `MMM d, yyyy` in `Locale.US` agree on every month
  abbreviation), so this is adoption with no visible change. `TITLE_FORMAT`
  (`ISO_OFFSET_DATE_TIME`, the machine-readable `title` attribute) stays.

### The coach prompt

One added rule, so the shape holds in the sentences the MODEL writes and not
only in the ones we render. Two sentences, one per line (RFC 185):

> Say a date as its short month name, the day and the year — `Sep 12, 2026` —
> including when a tool hands it to you as `2026-09-12`. Where a tool gives a
> month and a day and no year, say the month and the day and no year, because
> the school published no year and the year is not ours to add.

Self-consistency check against the rest of the file (the RFC 187 lesson): this
adds no claim a tool cannot source, contradicts nothing at lines 73-77 or
245-250, and the second sentence RESTATES line 73's existing no-year rule rather
than competing with it. The version is cut at land by `bin/prompt-seed`; the pin
today is `v38` and the number this run leaves is not knowable until land.

### The guard, and why it is where it is

`db/src/test/kotlin/ed/unicoach/db/DateRenderingHasOneSeamTest.kt` — a NEW file
whose only reason to exist is to assert this, modelled on
`PartialDateStorageHasOneReaderTest` (RFC 209), which is the tree's existing
cross-module source sweep. Brief 0010's standing rule: a guard inside the
artifact under rewrite is not a guard.

It sweeps **every module's main source set, derived from `settings.gradle.kts`**
(not a hand-written list, which would name only the modules that existed the day
it was written), and refuses:

- `DateTimeFormatter` / `ofPattern`
- `TextStyle` / `getDisplayName`
- any string literal that is exactly a full or abbreviated English month name
  (`"January"`, `"Sep"`, ...) — this is what closes the bypass of hand-rolling a
  month array instead of calling a formatter

outside a permitted map, each entry carrying WHY, so a fourth cannot join by
resembling the first three:

| Permitted file                       | Why                                                           |
| ------------------------------------ | ------------------------------------------------------------- |
| `common/.../util/UsDate.kt`          | the seam itself                                               |
| `college/.../IpedsLoader.kt`         | `CLOSED_AT_FORMAT` PARSES a source feed; it renders nothing   |
| `service/.../coaching/CoachClock.kt` | `COACH_ZONE_LABEL` names a TIMEZONE, not a date               |
| `admin-web/.../render/CellRender.kt` | `TITLE_FORMAT` is `ISO_OFFSET_DATE_TIME`, a machine attribute |

Two anti-vacuity assertions, both from the RFC 209 precedent: the sweep fails if
it reads fewer than 15 module roots or fewer than 200 files, and it fails if the
permitted seam file no longer contains the banned tokens — a stale permit is a
test asserting about a file that moved.

## Files Modified

| File                                                                                        | Change                                                           |
| ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `common/src/main/kotlin/ed/unicoach/common/util/UsDate.kt`                                  | NEW — the seam                                                   |
| `common/src/test/kotlin/ed/unicoach/common/util/UsDateTest.kt`                              | NEW — the seam's own unit tests                                  |
| `db/src/main/kotlin/ed/unicoach/db/models/CdsMonthDay.kt`                                   | `phrase` delegates its month name                                |
| `db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt`                                   | `usDate()` extension over the sealed type                        |
| `db/src/test/kotlin/ed/unicoach/db/DateRenderingHasOneSeamTest.kt`                          | NEW — the guard                                                  |
| `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`                           | `renderGraduationPhrase`, `renderDateBlock`; `monthName` deleted |
| `service/src/main/kotlin/ed/unicoach/coaching/testing/AdmissionTestTimelineChatTool.kt`     | prose only; wire values unchanged                                |
| `service/src/test/kotlin/ed/unicoach/coaching/GraduationSentenceIsUnchangedTest.kt`         | re-pinned to the new shape, with a note naming this RFC          |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt`                       | expectations follow the shape                                    |
| `service/src/test/kotlin/ed/unicoach/coaching/testing/AdmissionTestTimelineChatToolTest.kt` | prose expectations; wire assertions unchanged                    |
| `public-web/src/main/kotlin/ed/unicoach/web/render/ExplainerPage.kt`                        | `As of` / `Fetched`                                              |
| `public-web/src/test/...` (as found)                                                        | expectations follow                                              |
| `admin-web/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt`                          | `DATE_FORMAT` deleted, seam adopted                              |
| `prompts/coach-system-prompt.md`                                                            | two added lines                                                  |

No `db/schema/**`. No `db/schema/ORDER` line. No seed. No DDL.

**`GraduationSentenceIsUnchangedTest` is edited on purpose, and it is the one
place this run touches a landed guard.** That file pins RFC 186's sentence
byte-for-byte so that RFC 209's storage rewrite could not move it. This RFC
moves it deliberately, under D21, and re-pins the new bytes at all five of its
cases — including the SENIOR and PAST-GRADUATION cases it already carries for
D22. The pin keeps working; what it pins is restated by an RFC with the
authority to restate it.

## Implementation Plan

1. `UsDate.kt` plus `UsDateTest.kt`. Nothing else compiles against it yet.
2. The guard, `DateRenderingHasOneSeamTest`. **It fails at this point** — five
   sites still offend. That failure is the evidence the guard is not vacuous,
   and it is recorded in the run scratch before step 3 makes it pass.
3. `:db` — `CdsMonthDay.phrase`, `PartialDate.usDate()`.
4. `:service` — coach block, graduation phrase, timeline prose. Re-pin
   `GraduationSentenceIsUnchangedTest`.
5. `:public-web`, then `:admin-web`.
6. The prompt lines.
7. `nix develop -c bin/test`, whole suite, no module, no `-f`.

## Tests

- **`UsDateTest`** — each entry point at each precision, including the
  month-only and day-absent forms, and the assertion that `monthDay` has no way
  to render a year.
- **`DateRenderingHasOneSeamTest`** — the sweep above, with its two anti-vacuity
  assertions and its stale-permit assertion.
- **`GraduationSentenceIsUnchangedTest`** — re-pinned: `on Jun 15, 2028`,
  `in Jun 2028 (the day is not recorded)`,
  `in 2028 (only the year is recorded —
  no month, no day)`, plus the senior
  and the past-graduation cases (D22: the cases are deliberately not one
  student's shape).
- **`AdmissionTestTimelineChatToolTest`** — a test that asserts, for one
  payload, that a wire field is still ISO AND that the same date reads
  `Sep 18, 2026` in the `why` sentence beside it. That is the one assertion that
  says these two shapes coexist on purpose.
- **A no-year test** — a CDS deadline with a month and no year renders with no
  year, through the seam, for a school whose cycle has none.
- **A year-only test** — a `PartialDate.YearOnly` renders `2028` and no month,
  at every surface that speaks it.
- Whole suite: `nix develop -c bin/test`, counted from the JUnit XML `tests=`
  attribute.

## Decisions taken at the approval gate

Five, all approved at their defaults on 2026-09-16 (Ian, verbatim: "approve
all"):

1. **Scope** — `:service` (the coach), `:public-web` (the explainer pages) and
   `:admin-web` (the operator tables) adopt the seam. **iOS is left alone**: it
   is Swift, cannot share a Kotlin seam, and already renders
   `.formatted(date: .abbreviated)` -- `Sep 12, 2026` in `en-US`. Its
   `OnboardingView.monthName` is a PICKER label in the user's own calendar and
   locale, not a rendered date.
2. **The coach's briefing line** moves from ISO to `Sep 12, 2026`, so the prompt
   does not instruct one shape and demonstrate another.
3. **The timeline tool's English sentences** take the US shape; **every JSON
   wire value stays ISO**. The two shapes coexist on purpose and a test asserts
   both in one payload.
4. **Two lines are added to the coach prompt**, and a version is cut at land.
5. **`Sep 2028`, never `September 2028`**, when the day is unknown. One rule, no
   exceptions -- which is also what makes the guard unarguable.
