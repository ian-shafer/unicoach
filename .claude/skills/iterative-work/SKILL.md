---
name: iterative-work
description: >-
  Role doctrine for multi-agent iterative work — the master / orchestrator /
  worker hats, their contracts, and the universal rules (capture-on-completion,
  scratch ownership, checkpoints, verify-on-stall, bounded fan-out, write-scope,
  independent verification) that keep a long autonomous run durable and
  resumable. Reference doctrine cited by rfc-pipeline, skill-loop, the review
  chains, and the per-rule workers — it is not invoked directly.
---

# Iterative Work — Role Doctrine

This is **reference doctrine, not a macro**: skills that spawn — or are spawned
by — other skills cite it for their role contract. It is never "run." A
consuming skill declares its hat in a one-line header, e.g.
`**Role:** this skill wears the **worker** hat — see iterative-work`, then keeps
its own domain mechanics inline and relies on this file for the general rules.

## The problem it solves

Work held only in an agent's memory until a large unit finishes is **forfeited if
that unit stalls**. The rules below make every sub-unit produce a **durable
artifact** and (for the master) a **checkpoint** the moment it completes, so a
stall costs at most the single in-flight unit — never a whole iteration. Two
failure modes this directly prevents: a stalled aggregator that loses every
finished sub-result it was about to synthesize, and a stalled fix that leaves a
broken tree no one re-verified.

## Roles are hats, not a fixed tier

A skill may wear **more than one** hat, and the same hat is worn by many skills.
Map by behaviour, not by name:

- **Master** — owns the whole run (e.g. `rfc-pipeline`).
- **Orchestrator** — owns a fan-out or a loop (e.g. `skill-loop`,
  `code-review-chain`, `design-review-chain`, `rfc-impl-review` — which also
  wears a worker-ish _checker_ hat for its own scope/test phases).
- **Worker** — does one unit (e.g. the `code-review-*` / `design-review-*`
  leaves, `spec-writer`, `invariants-writer`, `rfc-impl`, `rfc-impl-fix`).

## The Master hat

- **Owns the durable substrate**: the run's working surface (worktree/branch or
  equivalent), the recorded base revision, and the **run scratch directory**
  (`<run-scratch>`). Establishes `<run-scratch>` once and hands it — plus an
  exact sub-path — to **every** agent it spawns.
- **Owns the checkpoint ledger**: checkpoints the tree at every gate boundary
  (before/after each spawn, before each human review) and numbers every loopable
  step monotonically. A checkpoint is a restore point; recovery is a reset to it.
- **Enforces write-scope** on every returning agent — the footprint must be a
  subset of the declared allowlist — and resets on violation rather than keeping
  rogue writes.
- **Runs independent verification**: never relays a worker's "green"; re-runs the
  check itself and reads the real result.
- **Verifies on return _and_ on stall/kill** before trusting or resetting; a
  stalled unit is re-spawned against the **same** `<run-scratch>` and resumes
  where it left off.
- **Never does worker-level work inline** — delegates to protect its own context.
  It is the **only** hat that commits and runs human gates.

## The Orchestrator hat

- **Owns a bounded fan-out or an iterative loop**; spawns workers but owns none
  of the master's checkpoints or gates.
- **Caps concurrency**: spawns in bounded batches and refills as slots free, so a
  provider-throttling episode _slows_ the work instead of tripping many stream
  watchdogs at once.
- **Assigns each worker its scratch sub-path**, and on entry **skips any worker
  whose artifact already exists** — this is what makes a re-invoked orchestrator
  resume at the first incomplete worker.
- **Reconstructs its aggregation from worker artifacts** — reads the directory of
  results rather than holding them all in memory. A stalled compile is re-read,
  never re-run; no finished worker is lost or repeated.
- **Reports a compact rollup upward** (a summary plus the artifact path), not raw
  worker output.

## The Worker hat

- **Does exactly one unit** and reads **only what it is handed** — no reliance on
  global state or developer recollection.
- **Writes its result write-once** to its assigned scratch path **the instant it
  finishes — before its chat reply** — so the work survives even if the caller is
  interrupted. The chat reply is a summary; the artifact is the source of truth.
- **Idempotent**: if its artifact already exists, the unit is already done — do
  nothing.
- **Honors its write-scope allowlist exactly**; edits nothing outside it.
- **Never commits, never stashes** — the master owns all checkpoints; use
  `diff` against a recorded baseline if a comparison is needed.

## Universal rules (every hat)

- **Capture-on-completion** — write the durable artifact the moment a unit
  finishes, not at the end of the batch.
- **Skip-if-present** — an existing artifact means that unit is done; resume past
  it.
- **Scratch is master-owned, threaded down, and gitignored.** It survives a
  recovery reset (a reset + `clean -fd` removes untracked files but not ignored
  ones) — **never** `clean -x` it, which would delete the run's captured work and
  break resume.
- **Checkpoint at gate boundaries** (master only).
- **Never trust "green"** — verify independently, on return and on stall/kill.
- **Bounded fan-out** — cap simultaneous workers.
- **Write-scope ⊆ allowlist** — enforced from the footprint, not trusted from the
  self-report.
- **Smaller chunks** — size a unit to the smallest durable, verifiable result;
  the master owns the iteration boundary rather than handing a whole multi-step
  loop to one agent and waiting blind.

## The two durability layers

They are orthogonal and both keyed to the run:

- **Tree state → checkpoints** (git or equivalent) — what the work _produced_.
- **In-flight agent output → `<run-scratch>`** — verdicts, findings, ledgers, and
  drafts that would otherwise live only in an agent's memory.

A recovery reset rolls the **tree** back to a checkpoint but leaves `<run-scratch>`
intact (it is gitignored), so a re-spawned agent reads the scratch and continues
from the first incomplete unit.
