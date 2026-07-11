# Invariants — `coaching`

## Structured-output passes read the forced tool's `tool_use.input`

**Rule:** The four structured-output coaching passes (extraction, synthesis,
fit-lens query, fit-lens reason) MUST obtain their parsed payload from the
forced tool's `tool_use.input` object (`ContentBlocks.toolUseInput`), and MUST
send both the tool definition and `tool_choice: {type:"tool", name:…}` that
force it — never rendered assistant text.

**Why:** `ContentBlocks.renderText` renders `tool_use` blocks as empty. A
refactor that reads the payload with `renderText`, or drops `tool_choice` so the
model may answer in prose, silently produces a 100%-`NoToolUse` failure rate
that reads as a model problem, not a code regression — re-introducing the
unparseable-envelope failure class this design removes. The coupling is a
write-path discipline, not type-enforced.

## Every `ChatProvider` call goes through `LlmCallLog`

**Rule:** All LLM provider calls MUST be made through `LlmCallLog` (`record` /
`recordStreaming`); no code outside a process composition root may hold or call
a raw `ChatProvider`. Each composition root MUST construct the raw provider only
to wrap it in `LlmCallLog`, and inject only the `LlmCallLog`.

**Why:** The generic log is the sole record of every request/response/raw and
the single token ledger. A direct `chatProvider.chat/stream` call bypasses it —
silent, unrecorded spend and an unobservable call, exactly the gap this design
closes. The coupling is a wiring discipline, not type-enforced (the port stays
callable), so a refactor can reintroduce a bypass.

## Every logged request gets a terminal response row

**Rule:** Every `llm_requests` row `LlmCallLog` opens MUST get exactly one
`llm_responses` row before the opening call returns or propagates — a joint
discipline across the seam and its callers. (a) For a call whose stream is being
collected, `LlmCallLog`'s own flow writes the terminal row (`completed` /
`rejected` / `transient_failure`, or `cancelled` / `internal_error` on an
interruption, under `NonCancellable`). (b) Because `recordStreaming` commits the
request row eagerly and returns a **cold** flow, a caller that opens a streaming
call (`CoachingService.openUserTurn` / `openContinuation`) MUST, if interrupted
(cancellation _or_ a defect) before that flow is collected, write the missing
row itself via `LlmCallLog.writeCancelledIfAbsent` /
`writeInternalErrorIfAbsent`.

**Why:** A dangling `llm_requests` with no `llm_responses` is indistinguishable
from an in-flight call, a crash, and a silent drop, and understates token spend.
The subtlety: `LlmCallLog` alone cannot guarantee this — its response-writing
code lives inside a cold flow, so a request whose flow is never collected (an
opener/continuation interrupted in the gap after the eager request-row commit)
would be orphaned. The opener-side guards are therefore load-bearing, not
redundant. With both halves, the only cause of a dangling request is a hard
process crash. The guarantee is a write-path discipline (it deliberately races
structured concurrency), not a DB constraint, so a refactor that removes either
half can silently break it.

## All rows of one tool-use excursion share one `turn_id`

**Rule:** Every `convo_requests` row of one logical user turn — the
`kind = 'user'` opener and each `kind = 'tool_result'` continuation of its
tool-use excursion — MUST carry the same `turn_id`, minted once per user turn
and never re-minted mid-excursion. A continuation MUST reuse its opener's
`turn_id`.

**Why:** `turn_id` is the sole boundary the visible-exchange projection and the
extraction window group on. A continuation stamped with a fresh `turn_id`
fragments one excursion into phantom turns: the projection drops the
never-closed fragments and the extraction window can split mid-excursion —
re-introducing exactly the silent per-turn data loss the column exists to
prevent. The guarantee is a write-path discipline, not a DB constraint (no
trigger can check that a continuation reused the opener's value), so a refactor
can silently break it.
