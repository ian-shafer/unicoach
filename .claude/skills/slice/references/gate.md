# The gate messages

Every stop in /slice is one of the shapes below. Copy the shape; replace the
content. Each follows `operator-comms.md`: the label first, the internal name
expanded the first time, outcomes not mechanisms, one recommendation, a default.

## An ambiguous old letter

> YOUR DECISION REQUIRED — which slice is "S4"?
>
> Why now: the old single-letter names were reused across briefs, so "S4" names
> two different pieces of work and I will not guess. Option A —
> `first-value/04b/admissions-in-chat`: show admissions chances in the chat,
> brief 0001. Landed already as RFCs 140 and 148. Option B —
> `search/04/similar-colleges`: find colleges like one the student already
> likes, brief 0004. Not started. Recommend: B, because A is already landed. If
> you say nothing: I wait. Guessing costs a whole build run on the wrong thing.

## BLOCKS unmet — refuse, and claim nothing

> BLOCKED — `search/04/similar-colleges` cannot start yet.
>
> That slice finds colleges similar to one the student likes. It measures
> similarity over the percentile columns of the search index, and that index
> does not exist yet. The slice that builds it is `search/03/the-index`.
>
> To unblock: run `search/03/the-index` first. Nothing for you to decide.

## PREFER — one block, default to defer, never refuse

> YOUR DECISION REQUIRED — build the family cost report now, or after two other
> slices?
>
> Why now: `first-value/05/family-cost-report` (the cost summary a parent reads)
> can be built today. Two slices would make it better, and neither is
> technically required: `money/02/component-split` breaks the cost into tuition,
> housing and food instead of one blended number, and
> `money/03/comparison-contract` adds the five assumption lines a report needs
> when it compares schools. Option A — build it now. A parent sees a report
> sooner; it shows one blended number and no assumption lines, and we rewrite it
> twice. Option B — wait for those two slices. The first report a parent ever
> sees is the right one. Recommend: B. This is the artifact the whole brief is
> betting on. If you say nothing: B.

Record the answer verbatim. An override goes in the ledger line too ("run early
on Ian's call, 2026-08-31").

## DEFERRED — confirm once, like a PREFER

A deferred slice is parked by intent, not blocked by anything. It never appears
under READY on the board. If Ian says yes, remove the `**Status:** DEFERRED`
line as part of the close-out, because the board must match the decision.

> YOUR DECISION REQUIRED — start a slice that was parked on purpose?
>
> Why now: `search/06/unattended-refresh` (refresh the college data on a
> schedule, with nobody watching) is marked DEFERRED — parked by choice, not
> blocked by anything. Nothing stops it technically. Option A — start it now.
> Option B — leave it parked. Recommend: B, unless something changed since it
> was parked. If you say nothing: B, and the run stops here.

## CONFLICTS — warn only

It says two slices edit the same files, so the later one rebases. It is **not**
an ordering: a CONFLICTS pair may share a wave and run in parallel in separate
worktrees — expect a rebase, not a wait. A stale conflict note must never harden
into a blocker: `money/02/component-split` was recorded for weeks as blocked on
`search/03/the-index` and never was. Check for live runs before warning:

    $SHIP/ship-status
    git worktree list

With a live run on the other slice:

> FYI — NO ACTION NEEDED — `family/10/claim-from-the-report` is clear to start.
> It edits the same public-web entry pages as `family/02/invite-and-accept`,
> which is live right now in another worktree, so this run will rebase over that
> one before it lands. A rebase, not a wait.

With none: "clear to run — rebase risk only", in one line.
