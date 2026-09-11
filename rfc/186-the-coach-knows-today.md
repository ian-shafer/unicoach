# RFC 186 — the coach knows today

Product slice `test/01/the-coach-knows-today` (brief
`product/0010-when-do-i-test`, gate 1 D1-D14, gate 2 D15-D20).

## Problem

The coach does not know what day it is, and it does not know when the student
finishes high school — even though we asked for the second one at signup and
have stored it ever since
(`students.expected_high_school_graduation_year/
_month/_day`, schema `0005`).

So the coach cannot say the sentence the whole brief is built on: _"you are a
junior; registration closes in eight days."_ It knows neither half. Asked "how
long have I got?", today's product either asks the student what year they are in
— a fact we already hold — or answers from the model's own idea of the date,
which is whatever its training data suggested.

Every other slice in brief 0010 is blocked on this one. It is also worth landing
alone: every deadline question in the product is wrong without it.

There is a trap in the way. `CoachingService.composeSystem` (`:845`) begins:

```kotlin
if (pending.isEmpty() && pendingFits.isEmpty() && moneyProfile == null) return prompt.body
```

A context block appended below that line renders for a student with a money
profile, an open commitment or a fit suggestion — and silently never renders for
a brand-new student with none of the three, which is exactly the student asking
"when do I need to take the SAT?" for the first time. The early return has to be
removed, not worked around.

## Decision

**Every system text the coach receives carries three lines: today's date, the
student's expected high-school graduation date, and the year of school derived
from the two.** Always — for every student, on `startConvo` and on every
`postTurn`, whether or not anything else is pending.

Five decisions make that concrete.

**1. Today is a US Eastern date, and it is a date.** The two existing `Clock`
sites default to `Clock.systemUTC()` and UTC-today is not a US family's today —
between 20:00 and 24:00 Eastern, UTC has already turned over, so a coach reading
UTC would tell a student on the East Coast that a registration deadline passed
yesterday when it closes tonight. Gate-2 **D15**: the arithmetic is computed in
`America/New_York`, because that is the timezone College Board and ACT publish
and enforce their deadlines in, and the coach speaks **dates**, never "hours
left". Per-student timezone inference from the residency state is rejected: it
is wrong for the split-timezone states and buys nothing at date granularity.

So `CoachingService` gains `private val clock: Clock = Clock.system(COACH_ZONE)`
— a defaulted constructor parameter, the pattern `SynthesisService.kt:83` and
`FederalAidPolicyService.kt:150` already use. Production passes none; a test
passes `Clock.fixed(...)`. The default differs from those two sites in its zone
and not in its shape, and that difference is the decision above.

**2. The year of school is derived, never stored.** From two inputs only: the
current academic year and the graduation **year**. The school year turns over on
**1 July**, so the academic year ending is `today.year + 1` from July onward and
`today.year` before it; the grade is then

    grade = 12 - (graduationYear - academicYearEnding)

A student graduating in 2028, read on 2026-09-11, is in grade 11 — a junior. The
same student read on 2027-06-30 is still a junior; on 2027-07-01 she is a
senior.

The derivation reads **only the year**, so a graduation date recorded with no
month still yields a year of school, and the coach never has a month to invent.
The month and day are still rendered when they are recorded, at their stored
precision.

One exception, added at review (two lenses found it independently): a date
recorded **to the day or to the month** and already behind today is
`PAST_GRADUATION` at once, without waiting for 1 July. Otherwise the block
contradicts itself for three weeks each June — "expects to finish high school on
5 June 2027" beside "their year of school today is grade 12" — and the D3 signal
the refusal rests on does not fire for a student who has in fact finished. A
year-only date cannot answer the question and defers to the year rule, which is
what keeps a month-less record answerable at all.

**3. The derivation lives in the coaching module, not beside `PartialDate`.**
`PartialDate` is a domain-agnostic `:db` value type named by four row types.
"The US high-school year turns over on 1 July" is a coaching convention, not a
property of partial dates — the same argument `FederalAidPolicyService.kt:194`
makes for keeping Federal Student Aid's July-1 award-year rule out of
`AcademicYear`. Two features sharing a month boundary by coincidence is not a
shared abstraction. New file:
`service/src/main/kotlin/ed/unicoach/coaching/HighSchoolYear.kt`.

**4. Out of range is a named state, not a grade.** A stored date that puts the
student before grade 9 or past graduation does not produce grade 8 or grade 13:
it produces `BEFORE_HIGH_SCHOOL` or `PAST_GRADUATION`, each rendered as a
sentence. `PAST_GRADUATION` is what makes **D3**'s refusal reachable from data
rather than from the model's guess — the record itself says this person is not,
on what we hold, a high-school student, and the prompt tells the coach to check
what they are applying to before answering a timing question.

**5. The prompt gains the rules that make the block usable**, and nothing else.
`prompts/coach-system-prompt.md`, one sentence per line (RFC 185): use the given
date rather than asking what year the student is in; speak dates and whole days,
never hours; say only the precision recorded; unicoach coaches US undergraduate
applicants from high school and does not advise on the GRE, GMAT or LSAT (D3);
and the SAT is never the default noun — the SAT and the ACT are both college
entrance tests (**D13**). `bin/prompt-seed` cuts the version at land iff the
body differs; no seed migration is hand-written and the `systemPromptVersion`
pin is not touched here (RFC 181, RFC 185).

**No DDL.** No new table, no new column and no hand-written migration.
Everything read here is already stored.

What the prompt edit does cost is the GENERATED tail `bin/prompt-seed` writes
from the source file: `db/schema/seed-coach-system-prompt-v28.sql`, its
`db/schema/ORDER` line, and the `systemPromptVersion` pin in `service.conf`.
None of the three is authored by hand, and the script is idempotent, so this run
generates them at verify — the catalog-versus-file test refuses a tree where the
served row and the authored file disagree — and phase 6 regenerates them against
the tree that actually lands.

### What this deliberately breaks

Six test assertions currently assert `assertEquals(pinnedCoachBody, system)` —
"with nothing pending, the system text IS the prompt body, verbatim". That
property is what this RFC removes: there is now always a block. Those assertions
become "the body is still there, in full, at the top, and the date block follows
it". The verbatim-body invariant is not weakened elsewhere: the body still
appears byte for byte, and the seed/pin machinery is untouched.

## Detailed Design

### `HighSchoolYear` (new, `:service`, package `ed.unicoach.coaching`)

```kotlin
enum class HighSchoolYear(val label: String) {
  FRESHMAN("grade 9 (freshman year)"),
  SOPHOMORE("grade 10 (sophomore year)"),
  JUNIOR("grade 11 (junior year)"),
  SENIOR("grade 12 (senior year)"),
  BEFORE_HIGH_SCHOOL("not yet in high school") {
    override val clause: String = "they are not yet in high school."
  },
  PAST_GRADUATION("past their expected high-school graduation") {
    override val clause: String = "that date has passed, so on our record they are no longer a high-school student."
  },
  ;

  /** The sentence tail the coach is given, owned by the constant so no caller re-branches on it. */
  open val clause: String get() = "their year of school today is $label."

  companion object {
    /** The school year turns over on 1 July: July..December belong to the year ending next calendar year. */
    internal fun academicYearEnding(today: LocalDate): Int =
      if (today.month >= Month.JULY) today.year + 1 else today.year

    fun of(today: LocalDate, graduation: PartialDate): HighSchoolYear =
      when (val grade = 12 - (graduation.year.value - academicYearEnding(today))) {
        9 -> FRESHMAN
        10 -> SOPHOMORE
        11 -> JUNIOR
        12 -> SENIOR
        else -> if (grade > 12) PAST_GRADUATION else BEFORE_HIGH_SCHOOL
      }
  }
}
```

Pure: no clock, no session, no I/O. `today` is supplied by the caller, which is
the one place the clock is read.

The `clause` lives on the constant rather than in the renderer, and review is
why: a caller branching on `PAST_GRADUATION` also has to remember that
`BEFORE_HIGH_SCHOOL` does not fit the sentence either, and the first draft did
not — it would have said "their year of school today is not yet in high school".

### The rendered block

Appended to the prompt body first, before the commitments, fit-suggestion and
money-profile blocks, separated by a blank line exactly as they are from each
other:

```
Today's date is 2026-09-11 (Eastern Time — the timezone College Board and ACT publish and enforce their deadlines in).
The student expects to finish high school in June 2028; their year of school today is grade 11 (junior year).
```

The second line renders the graduation date at its **stored precision**, and
says so when it is short:

| stored       | rendered                                                 |
| ------------ | -------------------------------------------------------- |
| `2028-06-15` | `on 15 June 2028`                                        |
| `2028-06`    | `in June 2028 (the day is not recorded)`                 |
| `2028`       | `in 2028 (only the year is recorded — no month, no day)` |

and the tail after the semicolon is the `HighSchoolYear` constant's own
`clause`, so `PAST_GRADUATION` ends
`...; that date has passed, so on our record they are no longer a high-school student.`

The zone's NAME is derived from `COACH_ZONE` rather than typed
(`getDisplayName(TextStyle.FULL, Locale.US)` — "Eastern Time"), so moving the
constant cannot leave the coach claiming a timezone the code no longer uses. The
same words in `prompts/coach-system-prompt.md` are beyond any constant's reach
and are flagged as hand-maintained at the constant itself.

The precision cases dispatch on the SEALED `PartialDate`, not on its nullable
`month`/`day` projections: a fourth variant must then fail to compile rather
than render silently as "only the year is recorded".

### `CoachingService`

- New constructor parameter, last, defaulted:
  `private val clock: InstantSource = InstantSource.system()`, with
  `private val COACH_ZONE: ZoneId = ZoneId.of("America/New_York")` in the
  companion. Every existing call site — `Application.kt:406` and the tests —
  keeps compiling unchanged.
- **The clock supplies the instant; the service supplies the zone**, and the
  PARAMETER TYPE says so: `InstantSource`, not the `Clock` the two sibling
  services take, because a `Clock` advertises a zone this service then discards.
  Today is `LocalDate.ofInstant(clock.instant(), COACH_ZONE)`, never
  `LocalDate.now(clock)`: the second reads the injected clock's OWN zone, which
  would let a caller move the coach off Eastern and would make the "Eastern, not
  UTC" test pass for the wrong reason — it injects a UTC clock, so with
  `now(clock)` it renders the UTC date and the decision it is supposed to prove
  goes unverified. Review caught exactly that, executed.
- New private reader beside `activeMoneyProfile`:
  `graduationDate(session, studentId): PartialDate`, which is
  `StudentsDao.findById(session, studentId).getOrThrow()
  .expectedHighSchoolGraduationDate`.
  It is **not** null-tolerant: a convo's student row is guaranteed by a foreign
  key, so a missing row is a failure of the turn, matching
  `openExplicitCommitments`'s posture rather than `activeMoneyProfile`'s.
- Both call sites (`:270-277` `startConvo`, `:337-339` `postTurn`) read it in
  the pre-flight transaction they already hold and pass it to `composeSystem`.
  One extra statement on a session that already runs several.
- `composeSystem(prompt, graduation, pending, pendingFits, moneyProfile)`: the
  `if (... ) return prompt.body` line is **deleted**; the date block is appended
  unconditionally.

### The prompt

Appended at the end of `prompts/coach-system-prompt.md`, one sentence per line,
as its own region so that the parallel slice `test/02` (which edits the
admissions region) rebases cleanly:

```
Every conversation opens by telling you today's date, the date the student expects to finish high school, and the year of school those two work out to — use them, and never ask the student what year they are in or what today's date is.
That date is a US Eastern one, the timezone College Board and ACT publish and enforce their deadlines in: talk in dates and in whole days, never in hours left, and if something closes today, say whose timezone that is.
Say the graduation date only as precisely as it is recorded: where only a year is on file, say the year and never invent a month or a day.
unicoach coaches students applying to US undergraduate study from high school.
When the student's own words put them outside that — a college junior, a transfer or graduate applicant, a question about the GRE, GMAT or LSAT — say plainly that this is what you coach and that you do not advise on graduate or professional admissions tests, rather than answering anyway.
The same holds when the record says they are already past their expected high-school graduation date: ask what they are applying to before you answer a question about timing.
Never treat the SAT as the default test: the SAT and the ACT are both college entrance tests, and in a dozen states the ACT is the one the student will actually sit.
```

## Files Modified

| File                                                                              | Change                                                            |
| --------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/HighSchoolYear.kt`                  | **new** — the derivation and its labels                           |
| `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`                 | `Clock` param; graduation-date read; `composeSystem` restructured |
| `prompts/coach-system-prompt.md`                                                  | seven sentences (RFC 185: this file only; the seed is generated)  |
| `service/src/test/kotlin/ed/unicoach/coaching/HighSchoolYearTest.kt`              | **new** — the derivation table, boundaries included               |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt`             | new block tests; six verbatim-body assertions relaxed             |
| `db/schema/seed-coach-system-prompt-v28.sql` + `db/schema/ORDER` + `service.conf` | **generated** by `bin/prompt-seed`, never hand-edited             |

No hand-written migration and no hand-edited pin: the seed, the `ORDER` line and
`systemPromptVersion` are `bin/prompt-seed`'s output, regenerated at land.

## Implementation Plan

1. `HighSchoolYear.kt` + `HighSchoolYearTest.kt` — pure, no DB, lands green on
   its own.
2. `CoachingService`: `Clock`, `COACH_ZONE`, `graduationDate`, the
   `composeSystem` restructure, both call sites.
3. `CoachingServiceTest.createStudent` (the suite's own fixture, not the shared
   `CoachingTestDb` one, which no test here uses): let it take a graduation
   year/month/day so a test can place the student in a grade.
4. New `CoachingServiceTest` cases, including the no-profile case the early
   return used to swallow.
5. Relax the six `assertEquals(pinnedCoachBody, ...)` assertions to "body
   present in full, block follows".
6. Prompt sentences.
7. `nix develop -c bin/test` — full suite, no module, no `-f`.

## Tests

Executed, all in `:service`:

**`HighSchoolYearTest`** (pure)

- grade 9/10/11/12 for graduation years +4/+3/+2/+1 against a fixed today.
- the 1 July boundary: 2027-06-30 vs 2027-07-01 for a 2028 graduate — junior,
  then senior.
- `PAST_GRADUATION` for a graduation year already behind the academic year.
- `BEFORE_HIGH_SCHOOL` for a graduation year five years out.
- a `YearOnly` graduation date yields the same grade as a `FullDate` in the same
  year — the month is not consulted.
- a recorded date already behind today is `PAST_GRADUATION` before 1 July, the
  date itself is still `SENIOR`, a month-only date passes at the end of its
  month, and a year-only date defers to the year rule.
- every in-school constant's label names its own grade.

**`CoachingServiceTest`**

- **the acceptance criterion**: `startConvo` for a student with **no** money
  profile, **no** commitments and **no** fit suggestions — the system text
  contains today's date, the graduation date and the year of school. This is the
  `:851` trap, and it is the test that fails today.
- `postTurn` carries the same block.
- a fixed `Clock` yields the exact date string — Eastern, not UTC: an instant
  chosen at 2026-09-11T02:30Z renders 2026-09-10, proving the zone is applied.
- a year-only graduation date renders no month name anywhere in the block.
- a graduation date in the past renders the past-graduation sentence.
- the base prompt body is still present in full when the block is composed.
- the seeded catalog body contains the scope-refusal sentence and the
  SAT-is-not-the-default sentence (D3, D13) — a prompt edit that drops either
  fails the build.

**Honest limitation, stated at the gate.** D3's acceptance criterion says the
coach _says plainly_ that unicoach does not advise on the GRE/GMAT/LSAT. No test
in this repo drives a real model, so what is executed is that the instruction
and the derived `PAST_GRADUATION` signal both reach the model, not that the
model obeys. That is the same standard every prompt rule in the corpus is held
to.
