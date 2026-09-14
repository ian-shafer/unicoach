# RFC 203 — Budgets are not tests: delete the five numeric size ceilings

Status: proposed Slice: `concise/12/budgets-are-not-tests` (brief
`product/0009-say-it-once`, decision D20)

## Summary

Delete five numeric size assertions from the test corpus, together with the
constants they compare against. Keep every property test. Keep the cost report's
rendered word count as a MEASUREMENT that is computed and printed and asserts
nothing.

This is test-only and deletion-only. No production file changes, so there is no
behaviour change and the rollback is a plain revert.

## Motivation — the evidence, so this is not tried a third time

Brief 0009 D13 required "a rendered word-count assertion in `CostReportPageTest`
and a payload byte-budget assertion in `CollegeCostChatToolTest`, both on fixed
fixtures", because "a budget that only exists in an RFC is a number nobody
re-checks". The reasoning was sound. The mechanism failed, measurably.

`CostReportPageTest.WORD_CEILING` moved:

    1,767  ->  1,530  ->  1,450  ->  1,401  ->  1,404

The last step was **UPWARD**. No slice met the target its own spec set; each one
ratcheted the constant to whatever it had just measured. **A threshold edited by
the change it constrains asserts nothing.** It records history, and it taxes
every later change: brief 0011's `beyond/07/the-report-weave` would have had to
raise a ceiling in order to add a sentence a parent wants.

The deeper error is that size is only meaningful against the value it carries,
and no test can compute the value. Brief 0009's real finding was that
**repetition has zero value** — not that words are expensive. The ceilings
generalised that into "all words are expensive", so they block the honest
sentence and the third restatement identically.

**Keep the measurement, drop the gate.** Drift stays visible in the test output,
an RFC records the number beside the last landed one, and growth is judged by a
person at a gate — which is where the value half of the ratio lives.

### Superseded nomination — P2

Brief 0009 nominated **P2**: _"a ratchet constant is the measurement exactly,
never measurement plus headroom"_. The argument was that exactness is what makes
"raise this consciously" a true statement, and `concise/03` withdrew 57 bytes of
headroom on that reasoning.

D20 retires P2. The mechanism was wrong, not its calibration: conscious raising
happened all five times, once upward, and meant nothing, because the run raising
the constant was the run it constrained. The surviving, narrower lesson is D20's
own — **a limit the next honest change must edit is not a limit**.

No general review principle is nominated by this RFC, and none should be. Ian
declined one explicitly: one incident is not enough to codify a lens, and
writing a rule to police an artifact we are deleting is the same over-fitting
one level up.

## Detailed Design

### What is deleted

Five constants and their `assertTrue` call sites, named by D20:

| constant                     | file                                                                                  |
| ---------------------------- | ------------------------------------------------------------------------------------- |
| `WORD_CEILING = 1404`        | `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt`                    |
| `LEGEND_BYTE_CEILING = 6845` | `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`       |
| `LEGEND_BYTE_CEILING = 1359` | `service/src/test/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatToolTest.kt`    |
| `LEGEND_BYTE_CEILING = 621`  | `service/src/test/kotlin/ed/unicoach/coaching/MoneyProfileChatToolTest.kt`            |
| `LEGEND_BYTE_CEILING = 472`  | `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatToolTest.kt` |

The four `LEGEND_BYTE_CEILING` constants each back exactly one test —
`the tool description stays within its legend byte ceiling` — which measures the
PUBLISHED description's byte size and asserts it is under the constant. That
whole test goes, in all four files. Four `@Test` methods are removed and no
other assertion in those files changes.

Three of the four constants are the only member of their companion object
(`CollegeCostChatToolTest`, `FederalAidPolicyChatToolTest`,
`CollegeListChatToolTest`), so the now-empty companion object is removed with
them. `MoneyProfileChatToolTest`'s companion also holds the shared JDBC
connection and the `@BeforeAll` setup, so only the constant and its KDoc go.

`assertTrue` remains imported and used in all five files (127, 19, 29, 26 and
147 remaining call sites), so no import changes.

### What is KEPT, and is not weakened

Every property test stays byte-identical in what it asserts. They state what we
MEAN, cannot be satisfied by editing a constant, and cost a good change nothing:

- `the description is a legend, not a rulebook` — the phrase tripwire, in all
  four tool tests;
- `every figure the page speaks a kind for is covered by exactly one sentence`;
- the `MANDATORY_SURVEY` / `VOLUNTARY_SELF_REPORT` sentences rendering zero
  times;
- RFC 196's one-`href` source sweep.

Two of those property tests carry an explanatory comment that names
`LEGEND_BYTE_CEILING` as "the guard that actually bites" and calls their own
phrase list "a tripwire on known regressions, not the guard"
(`CollegeCostChatToolTest`, `FederalAidPolicyChatToolTest`). The comment prose
is corrected. **No assertion, no phrase in either list, and no test name
changes** — the correction is comment text only, and the honest half of the
comment (a phrase list catches known regressions, not new wordings) is kept
exactly as it stands.

This is called out here so the diff does not read as scope creep. Leaving those
sentences would be worse than stale: they would be an ACTIVE FALSE STATEMENT
about the safety of the code, telling the next reader that a deleted assertion
protects them — in the file most likely to be read by someone deciding whether a
phrase list is sufficient. RFC 201 corrected a comment in `bin/functions` for
the same reason, a comment that gave the wrong REASON for something.

The replacement says the true thing rather than merely dropping the false one. A
reader who learned only "there is no ceiling" might add one back, so the comment
now states that the phrase list is a tripwire on known regressions, that there
is NO size guard behind it and that this is deliberate (RFC 203), and that
growth is judged by a person at the /ship gate.

One section header in `CollegeCostChatToolTest` changes with it: "The legend
itself (RFC 192): its size, and the rules it must NOT carry" loses "its size,
and", because the size half of that section is what this RFC deletes.

### The measurement that survives

`CostReportPageTest` keeps the three-school render and the `visibleWordsOf`
count. The `assertTrue` goes; a `println` takes its place, and the test is
renamed to say what it now is:

    a three-school report's word count is measured and printed, never asserted

The line printed carries the ratchet chain, so the history that made the ceiling
worthless is beside the number a future reader will compare against:

    [MEASURE] three-school cost report: <n> visible words
    (RFC 203 deleted the ceiling; it had moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404)

**Where it is visible — the one open design question, decided here.** It goes to
the test's captured standard output, which Gradle writes into the JUnit XML at
`public-web/build/test-results/test/TEST-ed.unicoach.web.CostReportPageTest.xml`
(`<system-out>`) and into the HTML report. It is NOT put on the `bin/test`
console.

The alternative was `testLogging { events("standardOut") }` in
`public-web/build.gradle.kts`. It is rejected for two reasons. First, the
slice's acceptance criterion is that the diff touches nothing outside test
sources, and a build file is not a test source. Second, that switch is
module-wide: it would print every line every public-web test writes, so the one
number we care about would arrive inside noise the change did not ask for. A
grep is one command and names the number exactly:

    grep -h MEASURE public-web/build/test-results/test/*.xml

## Files Modified

Test sources only. Five files, all deletions except one renamed test and two
corrected comments.

- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` — drop
  `WORD_CEILING` and its KDoc; drop the `assertTrue`; rename the test and print
  the measurement.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`
  — drop the byte-ceiling test, the constant, its KDoc, and the emptied
  companion object; correct one comment in the legend property test.
- `service/src/test/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatToolTest.kt`
  — same, plus the same one-comment correction.
- `service/src/test/kotlin/ed/unicoach/coaching/MoneyProfileChatToolTest.kt` —
  drop the byte-ceiling test, the constant and its KDoc; the companion object
  stays.
- `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatToolTest.kt`
  — drop the byte-ceiling test, the constant, its KDoc and the emptied
  companion.
- `rfc/203-budgets-are-not-tests.md` — this file.

No production file, no build file, no migration, no schema, no seeded row.

## Implementation Plan

1. Delete the four `stays within its legend byte ceiling` tests and their
   constants, KDoc and emptied companion objects.
2. Correct the two comments that name the deleted constant as the real guard.
3. In `CostReportPageTest`, delete `WORD_CEILING` and the `assertTrue`, rename
   the test, and print the measurement.
4. `nix develop -c bin/format`.
5. `nix develop -c bin/test` — no module argument, no `-f`. Record executed
   counts.
6. Confirm the printed line is present in the JUnit XML, and record the measured
   word count in this RFC beside the chain above.
7. `git diff --stat` must list only the five test files and this RFC.

## Tests

No test is added. The change is subtraction, and its verification is that
everything else still passes.

- **Counts.** Four `@Test` methods are removed: `CollegeCostChatToolTest` 116 ->
  115, `FederalAidPolicyChatToolTest` 9 -> 8, `MoneyProfileChatToolTest` 26 ->
  25, `CollegeListChatToolTest` 28 -> 27. `CostReportPageTest` stays at 67 — its
  word test survives as a measurement. The executed totals from
  `nix develop -c bin/test` are recorded in the run report, and the drop must be
  exactly four.
- **The measurement really runs.** The number appears in
  `public-web/build/test-results/test/TEST-ed.unicoach.web.CostReportPageTest.xml`
  under `<system-out>`. A test that printed nothing would be the same silent
  failure this RFC is arguing about, so the run greps for it rather than
  assuming it.
- **Scope.** `ship-verify-scope` and `git diff --stat` against `BASE_SHA` show
  test sources and this RFC only.

### Measured result

`nix develop -c bin/test` (no module argument, no `-f`) on
`pipeline/rfc-203@7525d2a8`: **exit 0**, 3,181 tests, 0 failures, 0 errors, 0
skipped, plus the shell DB harness's 53.

The four `@Test` methods came out exactly as predicted — 116 -> 115, 9 -> 8, 26
-> 25, 28 -> 27, and `CostReportPageTest` unchanged at 67. Assertion call sites
dropped by exactly one per file (587 -> 586, 62 -> 61, 79 -> 78, 102 -> 101, 253
-> 252), which is the deleted ceiling and nothing else: no phrase list shrank
and no assertion loosened.

The measurement reached the test output, verbatim from
`public-web/build/test-results/test/TEST-ed.unicoach.web.CostReportPageTest.xml`:

    [MEASURE] three-school cost report: [1404] visible words (RFC 203 deleted the ceiling; it had moved 1,767 -> 1,530 -> 1,450 -> 1,401 -> 1,404)

**1,404 words is the number this RFC leaves the page at** — the same figure the
deleted ceiling held, because nothing about the page changed. It is recorded
here so the next reader has a landed number to compare against, which is the
whole of what the ceiling was ever giving us.

## Rollback

`git revert` of the code commit. There is no runtime behaviour to restore, no
migration to undo, and no flag.
