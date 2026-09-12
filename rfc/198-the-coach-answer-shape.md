# RFC 198 — The coach answer shape

**Slice:** `concise/06/the-coach-answer-shape` (brief 0009 "Say it once", the
last slice of the brief). **Lane:** rfc. **Gate-1 decision it implements:** D7
("a table is an acceptable coach answer format: three or more parallel figures
of the same kind render as a table, one row each; units live in the header,
once").

## Problem

Brief 0009 removed repetition from the two surfaces a family reads. The Family
Cost Report page went 1,767 -> 1,404 words (RFCs 188, 190, 191, 196). The
`college_cost_profile` payload went 4,980 -> 1,864 bytes per college (RFC 189).
RFC 192 gave every behavioural rule one home in the prompt and left the tool
description as a pure schema legend.

What is left is **delivery**. The payload is thin and each rule has one home,
but nothing tells the coach what an answer should LOOK like. The prompt says
what is true, what must be attributed, and what may never be said; it has never
said that a money answer opens on a number, that parallel figures belong in a
table, or that a caution is said once.

Two external findings from the brief's research make this a correctness
intervention rather than a style one:

- concise pages measure **58% better** usability and scannable pages **47%**
  better (NN/g, Morkes & Nielsen); readers read at most **28%** of the words.
- only **51.5%** of a generative assistant's sentences are fully supported by
  its citations (Liu et al., EMNLP 2023 Findings, arXiv:2304.09848). **More
  sentences means more unsupported sentences.** Fewer, denser sentences is a
  correctness bet.

## Decision

One new prompt paragraph — ten sentences, added at an existing paragraph
boundary, nothing reworded and no paragraph opener touched. (Eight at the
approval gate; two more added after review, on evidence from the evaluation
below.) The prompt is the only artifact this RFC changes; no Kotlin production
code, no wire key, no table, no column, no migration.

**The four rules (gate-1 D7).**

1. **Lead with the number.** The first sentence of a money answer carries a
   figure, says whose it is and says what it covers. The explanation, the
   caution and the method come after it.
2. **Three or more parallel figures are a table**, one row each, when they are
   the same kind of figure on the same basis — within the three-column limit the
   prompt already sets for phone width.
3. **The unit is said once, in the column header** — dollars, the academic year
   — never repeated in every cell.
4. **A caution is said once in an answer**, where it changes what the family
   would do, rather than beside every figure.

**And four guards, which are the reason this slice is safe.** Two were in the
design; two were added after review found real losses in the evaluation's raw
answers that the mechanical checks did not name.

5. **Deference, not reordering (D-A).** Where the result carries a
   `comparison_basis`, RFC 151's five basis sentences STILL come first, exactly
   as the comparison paragraph already says; the figures lead everything after
   them. This slice is ADDITION-only. Reordering a landed instruction is not
   something it may do, and the cost of that deference is measured below rather
   than hidden.
6. **"Once" is never "none" (D-B).** Saying a caution once is never licence to
   leave out a sentence the result gives you: the sentence beside a figure with
   no amount (RFC 183), the sentence about what kind of number a school's
   figures are (RFC 179 / RFC 191), OUR assumption about living at home (RFC 166
   §7) and the residency basis a price is on (RFC 157) are each said as their
   own paragraphs require, however short the answer is. That sentence is gate-1
   D1 written into the prompt.

7. **The living-arrangement split still leads (D-D, added at review).** Where a
   school reports its costs by living arrangement, RFC 152's split leads and the
   same four lines still follow in the same order; the figure you front-load is
   that arrangement's own, never one total standing in place of the split. Found
   by reading the draft's own answers, which opened on a single total — exactly
   what the committed instruction forbids. Same reasoning as D-A: defer to the
   landed rule, do not quietly reorder it.
8. **"Once" is never "far away" (D-E, added at review).** Naming a source once
   is not naming it at the end: the source and the academic year are said WITH
   the figure they cover, and a loan is said to be money paid back with interest
   every time one is named. The draft collapsed two per-figure attributions into
   one blanket publisher list, which is how a once-per-answer rule quietly
   becomes a once-per-answer ATTRIBUTION rule that RFC 177 never granted.

**One prohibition this RFC adds on its own authority (D-C).** Two figures this
prompt forbids you to add together or to compare never share a table: another
academic year, another residency, another way of living, or another publisher's
figure each belongs in its own table or its own lines. A table is a claim that
its rows are comparable. That is precisely the hazard RFC 175 D9 names on the
page side — a shared merit/debt table makes two un-summable publishers look
summable — and brief 0009's D9 forbade `concise/04` from merging them for that
reason. A chat table is a new route to the same defect, and the table rule above
is what opens it, so the prohibition lands in the same paragraph.

## Detailed Design

### The paragraph

Inserted between the comparison paragraph (which ends at the line
`Where a comparison gives one basis sentence for every school...`) and the
name-lookup paragraph's opener. Authored one sentence per line, as RFC 181
requires, so a concurrent run editing another paragraph merges rather than
conflicts:

```
Lead a money answer with the number: the first sentence carries a figure, says whose it is, and says what it covers.
The explanation, the caution and the method come after it, never before it.
Where the tool gives a comparison_basis, those five sentences still come first as the rule above says, and the figures lead everything after them: leading with the number orders an answer and never drops a basis, a source or a year.
Where a school reports its costs by living arrangement, the split still leads and the same four lines still follow in the same order, as the rules above say: the figure you open with is that arrangement's own, never one total standing in place of the split.
When three or more figures of the same kind sit on the same basis — the same line for several schools, or the ways of living at one school — put them in a table with one row each rather than a paragraph each, within the three-column limit above.
Put the unit in the column header once — dollars, the academic year — and never repeat it in every cell.
Never put two figures in one table that this prompt forbids you to add together or to compare: another academic year, another residency, another way of living, or another publisher's figure belongs in its own table or its own lines, with what it is beside it.
Say a caution once in an answer, where it changes what the family would do, rather than beside every figure.
That is never licence to leave out a sentence the result gives you: the sentence beside a figure with no amount, the sentence about what kind of number a school's figures are, our own assumption about living at home, and the residency basis a price is on are each said as the rules above say them, however short the answer is.
Nor is it licence to move a caution away from the figure it belongs to: name the source and the academic year with the figure they cover rather than in a list at the end, and say that a loan is money paid back with interest every time you name one.
```

MEASURED: the authored prompt goes **196 lines / 31,329 bytes (v30) -> 206 lines
/ 33,317 bytes**, +10 lines and +1,988 bytes. The prompt GROWS, and so,
measured, do the ANSWERS (+4.1% on the evaluation set below). This slice buys
ORDER and ATTRIBUTION, not brevity; the brief's compression came from the page,
the payload and RFC 192's de-duplication.

### Where it does NOT go

Not the `college_cost_profile` tool DESCRIPTION. RFC 192 made that a pure schema
legend, moved 17 behavioural rules out of it into the prompt, and pinned it at
`LEGEND_BYTE_CEILING = 6845` **exactly**, with no headroom. An answer-shape rule
is behaviour; putting one there would re-create the two-homes defect RFC 192
removed and breach a ceiling with zero slack in the same edit.

### Version and rollback

`bin/prompt-seed` cuts the version at land (RFC 181 / RFC 185) and owns the
`systemPromptVersion` pin; this run hand-writes no seed and does not touch the
pin's assignment line. It does append to the **author's** comment log above it.

Rollback is `COACHING_SYSTEM_PROMPT_VERSION=<the version before this one>`.
Unlike RFC 192's, this is a **WHOLE** rollback: RFC 198 changes no code, so
flipping the pin removes these rules entirely and leaves nothing stated in
another home.

## Files Modified

| File                                                                  | Change                                                                                        |
| --------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `prompts/coach-system-prompt.md`                                      | +10 lines: the answer-shape paragraph, after the comparison paragraph                         |
| `service/src/main/resources/service.conf`                             | the append-only author comment above the version pin, and the pin line the generator rewrites |
| `service/src/test/.../coaching/SystemPromptCatalogTest.kt`            | `CoachParagraph.ANSWER_SHAPE`, its accessor, and the two tests below                          |
| `db/schema/seed-coach-system-prompt-v<N>.sql`                         | GENERATED by `bin/prompt-seed`; `db/schema/ORDER` line by `ship-order`                        |
| `product/0009-say-it-once/research/concise-06-answer-shape-eval.json` | the evaluation: eighteen raw answers, the question set, the 52 checks, the results            |

No new table, no column, no hand-written migration, no wire key, no production
Kotlin change.

**On the seed and the pin, stated as it happened rather than as a plan.**
`bin/prompt-seed` was run DURING implementation, not only at land: the suite's
prompt tests read the body the runtime is SERVED, so without a seed the new
paragraph is not in the served body and every one of them fails. The branch
therefore carries a generated `seed-coach-system-prompt-v31.sql`, its `ORDER`
line, and the pin moved to `v31`. `bin/prompt-seed` is re-run inside the land
block after the final rebase, exactly as RFC 181 requires, and re-cuts the label
if another prompt-editing run landed first — two were live in other sessions
while this one was open. The version and rollback label recorded here are the
ones this run actually landed with.

## Implementation Plan

1. Append the sentences at the paragraph boundary. Delete nothing, reword
   nothing, touch no paragraph opener.
2. Add `CoachParagraph.ANSWER_SHAPE` and its accessor; the suite's existing
   drift guard then requires the opener to be a real line of the file.
3. Add the two tests below.
4. Run the named evaluation against the v30 body and the new body, read the raw
   answers, and fix what the checks could not see — which is how sentences 4 and
   10 came to exist, and why the evaluation has three arms rather than two.
5. `bin/test`, review, land.

## Tests

Both live in `SystemPromptCatalogTest`, i.e. **outside** the artifact this run
rewrites, and both read the body the runtime is SERVED rather than the file (RFC
192 D7). A guard inside the artifact under rewrite is not a guard.

1. `the served answer-shape paragraph leads with the figure and never drops a
   required sentence`
   — asserts all four D7 rules, both deference sentences (`comparison_basis` and
   the living-arrangement split), the table prohibition, each of the four
   never-drop items by name, and the two review-added guards (the source and the
   year said with the figure; the loan caution per mention); then the standing
   copy rules (no `room and board`, no `sticker`, no `award`, every mention of
   subtracting forbids it, no source codebook).
2. `the version before the answer-shape paragraph is selectable and does not
   carry it`
   — the rollback stated as a RELATION, not a literal: the LOWEST coach version
   carrying the paragraph must have a selectable predecessor — the highest
   version below it, not `first - 1`, so a future gap in the labels cannot fail
   this test for a reason unrelated to the rule it guards — and that predecessor
   must not carry the paragraph. A `v30` literal would be wrong the moment
   another prompt-editing run lands first (two were live in other sessions while
   this one was open), and "the version before the SERVED one" goes silently
   false as soon as any later version lands on top.

The suite's existing tests carry the rest: the nine byte-frozen sentences
against the immutable v19 row, the served-body-equals-authored-file pin, the
`CoachParagraph` drift guard, and the whole-body subtraction sweep.

## The evaluation — named, run three times, and honestly limited

The slice's criterion is "a named evaluation, not a vibe check": an explicit
question set of real money questions, answered before and after, and each answer
checked for the figures, statuses, tiers, years, publishers and the at-home
assumption.

### What could NOT be run, said first

There is **no Anthropic API key in this checkout** and `CHAT_PROVIDER=log` in
`.env.dev`, so the PRODUCTION coach could not be run at all. This is **not** a
production measurement. Three things differ from production: a different model
instance, no tool-calling loop (the tool result is handed over as text), and no
conversation history beyond the six questions. It is a controlled A/B of prompt
bodies on identical inputs, and nothing more. Said here rather than in a
footnote, because the numbers below are worth exactly what the method is worth.

### Method

- **Inputs are real.** The `college_cost_profile` payloads were captured from
  the DB-backed test harness — `payload-three-schools.json` (35,936 bytes;
  Sierra State University public/CA, Marlowe College private nonprofit, and the
  `Six Statuses U` fixture that carries all six figure statuses) and
  `payload-one-school.json` (11,173 bytes). Real shapes, real status sentences,
  real provenance and availability sentences; the dollar amounts are the suite's
  fixture constants. Student: residency CA answered, income band `48k_to_75k`,
  living plan `with_family`.
- **Three runs, one variable.** **v30** (the body on `main`), **v31 first
  draft** (this paragraph as it stood at the approval gate) and **v31 as
  landed** (after review). Same payloads, same six questions, same order, each
  told to treat the six as one continuing conversation.
- **No answering run was shown the checklist.** Each was asked for the coach's
  replies and nothing else, and told not to read any RFC, spec or test. A run
  that knows what is counted counts it for you.
- **Grading is mechanical** — 52 string and regex checks, identical on all three
  sets, written down and archived with the answers.
- **The counting rules**, so the integers are reproducible: a FIGURE is a `$`
  immediately followed by a digit, or a bare `N,NNN`; a WORD is a
  whitespace-separated token containing a letter or digit, after table pipes,
  table separator rows, `*` and `#` are stripped.

### The question set

| #  | Question                                                   | Payload      |
| -- | ---------------------------------------------------------- | ------------ |
| Q1 | What would Sierra State cost me?                           | one school   |
| Q2 | What do these three schools cost?                          | three school |
| Q3 | What if I live at home instead?                            | three school |
| Q4 | Which one is cheapest after aid?                           | three school |
| Q5 | How much debt do students leave with at these schools?     | three school |
| Q6 | Why is there no number for some of Six Statuses U's costs? | three school |

The 52 checks are the gate-1 D1 items each question can reach: the specific
figures, the five `comparison_basis` sentences, the `FigureStatusCopy` status
sentences, the `AssuranceTierCopy` tier sentences, the cited academic years
(2023-24 published, 2022-23 blended, the 2024-25 CDS cycle), the publishers, the
income band in dollars, the at-home zero said as OURS, the price-after-aid
attributed to its own publisher within two sentences of the figure, the loan
caution — and the negative checks: no loan subtracted from a price, the two debt
publishers never added or compared, no total invented from partial parts, our
gap never spoken as the school failing to report.

A figure check matches the NUMBER with or without a `$`, on all three sides: the
unit-in-the-header rule deliberately leaves a table cell reading `12,000`, and a
grader demanding `$12,000` would have scored its own rule as a loss.

### Result

| Question      |       v30 | v31 draft | v31 landed | Words (v30 -> draft -> landed) | Words before the first figure |
| ------------- | --------: | --------: | ---------: | -----------------------------: | ----------------------------: |
| Q1 one school |     10/11 |     10/11 |  **11/11** |              346 -> 340 -> 358 |               9 -> 8 -> **5** |
| Q2 comparison |     10/10 |     10/10 |      10/10 |              391 -> 382 -> 437 |         157 -> 176 -> **175** |
| Q3 at home    |       4/5 |       4/5 |    **5/5** |              193 -> 206 -> 225 |                15 -> 11 -> 14 |
| Q4 after aid  |       5/7 |       5/7 |    **7/7** |              197 -> 191 -> 209 |                 11 -> 6 -> 15 |
| Q5 borrowing  |     11/11 |     11/11 |      11/11 |              368 -> 361 -> 388 |        84 -> **10** -> **10** |
| Q6 statuses   |       8/8 |       8/8 |        8/8 |              304 -> 243 -> 256 |             148 -> 127 -> 141 |
| **Total**     | **48/52** | **48/52** |  **52/52** |    **1,799 -> 1,723 -> 1,873** |                             — |

**Reachability went UP: 48/52 -> 52/52.** The four gained items are the
price-after-aid attributed to the College Scorecard at the figure (Q1 and Q4),
the publisher named at all in the after-aid answer (Q4), and the at-home zero
said as OURS in the "what if I live at home" answer (Q3). Three of those four
were already missing in v30: this paragraph did not lose them and then recover
them, it fixed gaps that were there before.

**THE ANSWERS GOT LONGER, NOT SHORTER: 1,799 -> 1,873 words, +4.1%.** That is
the honest headline and it is the opposite of what a brief called "Say it once"
wants to read. The cause is item 8 of the paragraph, added after review: saying
the source and the academic year WITH each figure, and the loan caution at every
mention, costs words. The trade made here is deliberate — +4.1% words for +4
reachable D1 items — but nobody should read this slice as a compression win. The
brief's compression came from the page (1,767 -> 1,404 words) and the payload
(-62.6%); the coach side bought ORDER and ATTRIBUTION, not brevity.

**Front-loading works where it is allowed to.** Q5 went from 84 words before the
first figure to 10: v30 opened on the cohort frame and reached the number in the
fourth sentence, the landed body opens on the figure and puts the cohort, the
year and the school straight after it, with both publishers still separated. Q1
went 9 -> 5. Q4 went 11 -> 6 in the draft and back to 15 in the landed body,
because the landed lead sentence now carries the publisher and the year with the
figure — the same trade as above, visible in one cell.

**The table rule fired.** v30 used **no table** across six answers; the draft
used three and the landed body four, all with the unit stated once
(`2023-24,
dollars`) and never in a cell. A mechanical check over every table in
all eighteen answers found **no table mixing two academic years, or a Common
Data Set borrowing figure with a College Scorecard one**.

**But the table prohibition was never actually exercised**, and the RFC will not
claim otherwise. Every table produced held 2023-24 IPEDS published figures. Q5 —
the one question where the school's own Common Data Set and the College
Scorecard meet, which is the RFC 175 D9 hazard item D-C exists for — produced no
table at all, on any of the three bodies. The rule is a guard against a shape
the model did not reach for here; the evidence that it HOLDS is the absence of
the bad shape, not a test of the rule.

**The unit rule was followed in spirit and not to the letter.** The model put
the unit in a bold caption line above each table
(`**Tuition and fees — the price the
school sets and publishes (2023-24, dollars)**`)
rather than inside the column header cell. It is said once, which is the point,
but the sentence says "column header" and that is not literally what happened.
Recorded as an open item rather than reworded, because the rule as written is
the clearer instruction.

### WHAT THE MECHANICAL CHECKS COULD NOT SEE — and what it changed

The draft scored 48/52, the same as v30, and a run that stopped there would have
reported "no loss". Reading the eighteen raw answers found two real losses that
no check named, both in the draft:

1. **Attribution collapsed into a blanket line.** v30's Q1 said "The published
   cost of attendance and the net price come from the College Scorecard"; the
   draft said "The figures come from ... IPEDS ... and ... College Scorecard",
   so WHICH publisher publishes the $14,000 was no longer stated. "Say a caution
   once" had generalised itself to "say a source once".
2. **The at-home assumption was dropped from an answer that named the zero**,
   defensible under the once-per-conversation rule and still a loss of the RFC
   166 §7 item.

Both are why sentence 8 exists — _"Nor is it licence to move a caution away from
the figure it belongs to: name the source and the academic year with the figure
they cover rather than in a list at the end, and say that a loan is money paid
back with interest every time you name one."_ — and why sentence 4 exists, which
keeps the living-arrangement split leading the answer rather than one total
standing in for it. The landed body was then re-run as the third evaluation set,
which is where 52/52 comes from. **The finding is the method's, not the rule's:
the checks found nothing; reading the answers found two things; the two fixes
scored four.**

### The evidence

`product/0009-say-it-once/research/concise-06-answer-shape-eval.json` carries
all EIGHTEEN raw answers verbatim, the question set, the 52 check names, the
counting rules, the caveats and the per-question results. It is committed, not
left in `.scratch/`, because an evaluation nobody can re-read is a claim rather
than evidence. The two payload dumps and the throwaway test that produced them
were transient and are not committed; the fixture colleges named above are the
harness's own and are reproducible from it.

## Open items

1. **A comparison still takes five sentences to reach a number** — measured, 175
   words before the first figure against v30's 157. Gate-1 D7's front-load rule
   is satisfied for single-school answers (Q1 9 -> 5) and for the borrowing
   answer (Q5 84 -> 10), and **structurally blocked for comparisons** by RFC
   151's committed "say those five lines first". A future slice that IS licensed
   to reorder could lead with the headline figures and put the basis immediately
   after, dropping no basis sentence. This RFC may not, and did not.
2. **The answers got longer (+4.1%)**, and a future slice could win some of it
   back without losing an item: the source-and-year-at-the-figure rule is what
   costs the words, and a shorter form of the same guarantee may exist.
3. **The unit rule says "column header" and the model used a bold caption.**
   Said once either way. If a later run wants the letter of it, that is a
   rewording of a sentence this RFC introduced, which is cheap; it was left as
   written because "column header" is the clearer instruction.
4. **The table prohibition (item D-C) was never exercised by the evaluation.**
   No answer put two publishers' debt figures anywhere near one table, so the
   guard is untested against a real attempt. An untestable case is not the same
   as an impossible one (RFC 191's own finding); the guard is cheap and stays.
5. **The evaluation is not reproducible in CI and is not a test.** It needs a
   model, and there is no key in the repo. The committed guards are the two
   prompt assertions; the evaluation is evidence recorded with its inputs and
   method.
6. **An unlicensed derived difference appeared once.** In one answer the model
   computed an off-campus-versus-at-home difference; prompt line 71 licenses
   only the at-home-versus-on-campus one. The arithmetic was right and both
   figures were the school's own, so nothing false was said, but it is a rule
   the prompt does not grant and a later run may want to grant or forbid
   explicitly.
7. **SD10 is still open and is not this run's to widen**: three more chat tools
   carry behavioural rules duplicated between their DESCRIPTION and the prompt.
