# RFC 257 — The coach sees the college list

**Status:** proposed. **Base:** `main@5b246226`.

The coach is told about the student's college list in a dozen prompt rules, and
never shown it. Every turn's system text now carries the list as it stands —
each school's name, `college_id`, status, the student's reasons and any
per-school living plan — so the coach can talk about "your list" without
guessing, and can use a listed school's id without looking it up again.

## Context

Five facts from the code decide this design.

1. **The coach cannot see the list today.** `CoachingService.composeSystem`
   composes the prompt body, the date block (RFC 186), the opener reflections
   (RFC 93/98), the money-profile block (RFC 134) and the test-scores block (RFC
   207). Nothing reads `college_list_entries`. The only list tool,
   `update_college_list` (RFC 136), is write-only: it echoes the list after a
   successful write and at no other time.
2. **The prompt assumes it can.** `prompts/coach-system-prompt.md` says "when
   the student has schools on their college list, bring real cost into the
   conversation early", "with an empty college list you may still give the
   general picture", and "never carry one school's policy across to another
   school on their list". The coach has no way to tell which case it is in.
3. **Two tool errors point at a read that does not exist.**
   `CollegeListChatTool.ECHO_FAILED_REASON` says "read the list again" and
   `CONFLICT_REASON` says "read it again and retry".
4. **Per-turn context blocks are the house pattern.** Money profile, test scores
   and the date all ride every turn, read in the turn's pre-flight session and
   composed by `composeSystem`; a failed read fails the turn.
5. **The list is small by construction.** A student curates it by hand
   (`CollegeListChatTool` already scans the whole active list on every call).
   `reasons` is capped at 2,048 characters by
   `college_list_entries_reasons_length_check`.

## Detailed Design

### D1. A context block, not a tool

The list goes into the system text on every turn. A read tool was considered and
rejected: a tool only helps when the model decides to call it, and the failure
Ian reported is the coach not knowing it should. The block costs one indexed
read plus one batch name lookup per turn, the same shape the money and scores
blocks already pay. No new tool is registered.

### D2. The read, in the turn's own session

`CollegeListService.activeWithNames(session, studentId)` (private today) moves
to a top-level `readActiveListInSession(session, studentId)` in
`ed.unicoach.coaching.collegelist`, following `readTestScoresInSession` (RFC
207). `CollegeListService.listActiveWithNames` and `listShared` call it;
`CoachingService` calls it in both `startConvo` and `postTurn` pre-flight
blocks, beside `activeTestScores`. `CoachingService` gains no constructor
parameter. It throws on a failed read, so a DB outage fails the turn rather than
telling the coach the list is empty.

Order is the DAO's: `created_at, id` — the order the student added schools.

### D3. What the block says

Rendered by one pure function, `CollegeListBlock.render(list)`, in a new
`CollegeListBlock.kt` beside the tool; the copy is named constants there so the
tests assert against them (the `TestScoreCopy` precedent). It is appended
**last** in `composeSystem`, so everything before it keeps its current bytes.

Non-empty:

```
College list (the student's own, as it stands at the start of this turn, in the order they added each school):
- Ohio State University: considering; college_id 3f1c…; living plan at this school: living at home; reasons: "close to home and strong in nursing"
- Kenyon College: applying; college_id 9a02…
```

- **name** — the colleges row name, as the tool echo gives it.
- **status** — `CollegeListEntryStatus.value` (`considering`, `applying`,
  `admitted`, `rejected`). These are already plain words, not codes.
- **college_id** — verbatim, so the coach can pass it to `update_college_list`,
  `college_cost_profile` and `college_admissions_profile` without a
  `find_college` round-trip.
- **living plan** — only when the school has its own; spoken through
  `LivingArrangement.label`, never the wire value (the RFC 142/152 rule:
  whatever the coach reads here it may say aloud). Absent otherwise; the money
  block already gives the usual plan.
- **reasons** — only when present, verbatim, as one JSON string literal, so a
  quote or a line break in them cannot end the quotes or start a line that reads
  as another school. They are the student's own words from their own coach
  conversations; the RFC 250 rule that keeps reasons from a linked adult does
  not apply to the student's own coach.

Empty:

```
College list: empty — the student has not added any school yet.
```

The empty case renders a line rather than nothing (unlike the scores block,
which is absent when never asked). An absent block cannot be told apart from
"the coach was not told", and the prompt has rules that turn on an empty list.

### D4. Freshness inside one turn

The block is composed once per turn, before the first provider call. If
`update_college_list` writes during the turn, its echo — the full list after the
write — is the newer truth for the rest of that reply; the next turn recomposes
the block from the database. No mid-turn recomposition: the continuation calls
reuse `Prepared.system` as today.

### D5. The two tool errors

- `ECHO_FAILED_REASON` → "the write succeeded, but reading the list back failed;
  the change is saved and the next turn's college list will show it".
- `CONFLICT_REASON` → "the list changed underneath this call; call the tool
  again". (The tool re-reads the active list on every call, so a plain retry is
  correct.)

### D6. The coach copy

In the college-list paragraph of `prompts/coach-system-prompt.md` (after "The
student's college list is theirs…"), one sentence per line:

- Your system text gives you the student's college list as it stood when their
  message arrived: read it before you say anything about their list, and never
  ask them what is on it.
- After update_college_list changes the list, the list it returns is the current
  one for the rest of your reply.
- A school already on their list needs no lookup: use the college_id the list
  gives you.
- Never show the student a college_id.

`bin/prompt-seed -b BASE_SHA` writes the seed migration, its `db/schema/ORDER`
line and the `systemPromptVersion` pin; none is hand-written.

## Files Modified

**New**

- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListBlock.kt`
  — the renderer and its copy constants.
- `service/src/test/kotlin/ed/unicoach/coaching/CollegeListBlockTest.kt`.

**Changed**

- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListService.kt`
  — `activeWithNames` becomes top-level `readActiveListInSession`.
- `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt` — the read
  in both pre-flights, the block in `composeSystem`.
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatTool.kt`
  — the two error strings (D5).
- `prompts/coach-system-prompt.md` — D6.
- The `bin/prompt-seed` outputs (seed migration, `db/schema/ORDER`, the pin in
  `service.conf`).
- Existing tests that pin the exact composed system text, where the new block
  changes it. They are updated to expect the block, never loosened.

Not touched: the schema, `Application.kt` (no new tool), REST, iOS,
`product/**`.

## Implementation Plan

1. Extract `readActiveListInSession`; `CollegeListService` uses it.
2. `CollegeListBlock` + its unit tests.
3. `CoachingService`: read in both pre-flights, append the block last.
4. The two tool error strings.
5. Prompt copy; run `bin/prompt-seed -b BASE_SHA`.
6. Fix any existing exact-system-text test to expect the block.
7. Full suite: `nix develop -c bin/test`, no module, no `-f`.

## Tests

**Renderer (pure, `CollegeListBlockTest`)**

- An empty list renders exactly the empty line.
- Two schools render in the given order, each with name, status and
  `college_id`.
- A school with its own living plan renders the label (`living at home`), never
  the wire value (`with_family`); a school without one says nothing about a
  plan.
- A school with reasons renders them quoted and verbatim; one without says
  nothing about reasons.
- Reasons holding a quote and a line break stay on their school's line, escaped.

**Composed into the turn (DB-backed, `CollegeListBlockTest`, scripted provider
capturing `ChatRequest.system`)**

- `startConvo` for a student with no list carries the empty line.
- `startConvo` for a student with two schools carries both lines, after every
  other block.
- `postTurn` reflects a change made between turns: add a school through
  `CollegeListService`, post a turn, the new school is in the system text;
  remove it, post again, it is gone (a soft-deleted entry never renders).
- A school added by `update_college_list` mid-turn is NOT in that turn's
  continuation system text (D4 is as designed, not accidental).
- A failed list read fails the turn: with `college_list_entries` renamed away,
  `startConvo` and `postTurn` both return a failure and the provider is never
  called — the coach is never told an unreadable list is empty.

**Tool**

- `CollegeListChatToolTest` asserts the two errors through the constants, and
  neither string says "read".

**Prompt**

- The D6 sentences are present in the seeded prompt, asserted from the file's
  lines, and `SystemPromptCatalogTest` stays green after `bin/prompt-seed`.

## Open questions for the approval gate

1. Block vs. tool (D1). Default: block, every turn.
2. Include the student's reasons (D3). Default: yes — the coach wrote them down
   for exactly this.
3. Render an explicit empty line (D3). Default: yes.
