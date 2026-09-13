# Talking to Ian

The one reader of every message these skills emit is Ian. He is the operator,
not a passenger of the run: he decides, he vetoes, he pays for the time. He has
NOT read this skill, the brief, the RFC, or the run state, and he should not
have to.

This file is the shared contract for all operator-facing output from `/ship`,
`/slice`, `/chart` and `/principle`. It governs chat messages, gate
presentations, progress updates and final reports. It does not govern files on
disk (an RFC, a brief, a spec) — those have their own readers and their own
conventions.

Three rules, in order of how often they are broken:

1. Expand an internal name once — the first time it reaches him — then shorten
   it.
2. Put every operator decision in the `YOUR DECISION REQUIRED` block.
3. Lead with the outcome, in plain words.

## 1. Expand an internal name once, then shorten it

A bare identifier is not information. `D33`, `S4`, `tier 2`, `lane A`,
`BASE_SHA`, `the door` are all names for something Ian cannot look up from a
chat message.

**The rule.** Expand a name the first time it reaches Ian **in this
conversation** — not the first time in each message. Write the name, then what
kind of thing it is, then its content in a few plain words. After that, use the
short form.

    BAD   Blocked by D33, so S4 stays in wave 2.

    GOOD  Blocked by D33 — decision 33 in brief 0003 (money), which says a
          cost report never subtracts loans from a published price. So
          `money/04/aid-letter` (the slice that reads a family's aid letter)
          waits for the next batch of work rather than starting now.

**Do not re-expand.** A full expansion repeated in every message is its own kind
of noise, and it buries the sentence that matters. Three cases only justify a
second expansion:

- the run is deciding ON that name right now, and the decision turns on its
  exact wording — then quote it again;
- the session was compacted or resumed, so the earlier expansion is gone;
- a long gap, or a different name that is easy to confuse with it.

Otherwise use a **short gloss** — two or three words in brackets — or nothing:

    First time   Blocked by D33 — decision 33 in brief 0003 (money), which says
                 a cost report never subtracts loans from a published price.
    After that   D33 (no loan subtraction) still applies.
    Later still  D33 still applies.

A name that appears once in the whole run is better written as words with no
identifier at all. The identifier earns its place only when Ian may need to look
it up or quote it back.

**Never invent a new short name in a message.** If the name is not already in a
committed doc, use words.

**Never abbreviate the thing that carries the decision.** Quote the decision
text. A decision Ian is asked to rely on is quoted, not cited.

### The names that need an expansion the first time

| Name in the docs             | Say this, the first time it reaches Ian                                                                       |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `D12`, `D33`                 | decision 12 in brief 0001 (first-value): "<quoted text>"                                                      |
| `S4`, `S3.5`                 | the old letter for a slice — give the full slice ID and its purpose                                           |
| `search/04/similar-colleges` | name it once in words: "the slice that finds similar colleges"                                                |
| `RFC 149`                    | the design doc for this change, number 149                                                                    |
| tier 0 / 1 / 2 / 3           | review pass by size of fix: 0 = does the code match the design, 1 = structural, 2 = one file, 3 = a few lines |
| lens                         | one review rule, run on its own                                                                               |
| lane A / lane B              | lane A = needs a written design first; lane B = mechanical change                                             |
| the hook / the gate          | the pre-commit check: the full test suite plus formatting                                                     |
| `BASE_SHA`, fork point       | the commit this work started from                                                                             |
| `db/schema/ORDER`            | the file that fixes the order database migrations run in                                                      |
| run scratch, `<rs>`          | the run's state directory on disk                                                                             |
| the door                     | how a real user reaches the feature (a chat tool, a screen, a link)                                           |
| wave                         | a group of slices that can be built at the same time                                                          |
| BLOCKS / PREFER / CONFLICTS  | BLOCKS = cannot start; PREFER = could start, we would rather not; CONFLICTS = same files, expect a rebase     |
| DEFERRED                     | parked on purpose, not blocked                                                                                |
| the aha                      | the moment the user first sees the value                                                                      |

If a name is missing from this table, the rule still applies. The table is
examples, not a closed list.

## 2. Every decision uses one block

A decision buried in a paragraph is a decision Ian will miss. Every point where
the run needs an answer from him uses this exact shape, at the TOP of the
message:

    YOUR DECISION REQUIRED — <the question in one line>

    Why now:   <=2 lines. What the run hit, and why it cannot choose alone.
    Option A (recommended) — <what happens if you pick it>
    Option B — <what happens if you pick it>
    Recommend: A, because <one reason>.
    Cost of being wrong: <how hard it is to undo, in one line>
    If you say nothing: <the default that will be taken>

Rules for the block:

- **One decision per block.** Several decisions are several blocks, numbered
  `1.`, `2.` in the message.
- **Two to four options.** More than four means the question is not framed yet.
- **Always recommend one, always give a default.** "Approve" must cost one word,
  disagreement one line. A block with no recommendation pushes the work back on
  the reader.
- **Options are outcomes, not mechanisms.** "Store the offer as a row per
  component" is a mechanism; "the report can show a per-component split next
  year without a migration" is the outcome. Give the outcome first.
- **State cost of being wrong only when it is real** — a new table, a data
  deletion, a public copy change, anything hard to reverse. Omit the line for a
  cheap, reversible call.
- **No jargon inside the block.** Rule 1 applies hardest here.
- **Do not ask again.** Once answered, record the answer and proceed. Re-asking
  is the failure this block exists to prevent.

### Label every interruption

Ian triages on the first line. Use exactly one of these labels, first line, no
preamble:

| Label                    | Means                                                          |
| ------------------------ | -------------------------------------------------------------- |
| `YOUR DECISION REQUIRED` | The run has stopped. It cannot continue without an answer.     |
| `FYI — NO ACTION NEEDED` | A default was taken. You can veto later; nothing waits on you. |
| `BLOCKED`                | Something outside this run must change before it can continue. |
| `DONE`                   | Terminal outcome: landed, or abandoned. Includes the result.   |
| `WORKING`                | Progress update. Nothing needed from you.                      |

A terminal outcome — landed, failed, blocked — is announced the moment it is
known, before any other content in the message.

## 3. Lead with the outcome, in plain words

**The first sentence says what changed for a user, or what the run now knows.**
Machinery comes after, and only when it explains a blocker or a decision.

    BAD   Tier 2 completed with 3 findings applied, 1 discarded; ship-rebase
          is a no-op; PHASE=verifying.

    GOOD  WORKING — the cost report now shows tuition, housing and food as
          separate lines. Review found three small fixes; all are applied.
          Next: the full test suite, about 6 minutes.

Style, applied to every message:

- Short sentences. One fact per sentence.
- Common words. Expand an acronym the first time.
- Real numbers, never vague ones: tests executed, findings applied, minutes
  waited, the landed commit SHA.
- Keep exact names, paths, commands, SHAs and quoted text unchanged. Plain
  English is for the prose around them.
- State uncertainty directly: "I have not tested the resume path" beats "should
  be fine".
- Do not paste long logs into chat. Quote the failing lines verbatim, then give
  the path to the full log.
- A command that succeeded is not news. The landed SHA is.
- Never end a turn on a tool result with no text. A tool result is not a report.

### The progress update

Send one at each real milestone, and before ending a turn while work is still
running. Keep it to this shape:

    WORKING — <what is true now, for a user or for the decision>
    Done:    <the step that just finished, and its result in numbers>
    Now:     <what is running, and roughly how long>
    Next:    <the step after that>
    Waiting on you: <nothing | the decision block above>

Do not repeat an unchanged status. Do not interrupt short work with an update.

### The final report

Same rules. Open with `DONE`, then one sentence a non-engineer could repeat,
then the detail: what landed, the SHAs, the executed test counts, what was
decided, what is still open. Every open item states who must act.
