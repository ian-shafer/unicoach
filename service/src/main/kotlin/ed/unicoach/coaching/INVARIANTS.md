# Invariants — `coaching`

## Every provider call in the tool-use loop records its own usage

**Rule:** Each provider call the chat tool-use loop makes MUST persist its own
`convo_responses` row (carrying that call's `TokenUsage`) before the loop makes
the next call or returns — including tool-continuation calls, the forced
no-tools cap call, and calls that fail. No billed call may be made without
recording a response row.

**Why:** The per-student token ledger and per-turn audit derive from
`convo_responses`. A continuation billed without a row is spend that happened
but was never recorded, silently understating student usage and losing the
provenance of a turn that made multiple model calls.

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
