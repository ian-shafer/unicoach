# Async work

Durable, cross-cutting rules for asynchronous work. These govern every
request-handling surface (`rest-server`, `admin-web`, and future ones) rather
than a single directory, so they live here at the repo root rather than in a
per-directory `INVARIANTS.md` (the per-directory mechanism is retired — see
CLAUDE.md — but the existing files, including the one cited below, remain in
place). They complement, and do not contradict, the `rest/routing/INVARIANTS.md`
rule for best-effort side-effects: that rule governs _recoverable_
fire-and-forget work (extraction); these govern async-capable work in general,
and _required_ side-effects (email) in particular.

Each is prescriptive and human-gated. Treat this file as reviewed intent, not
proof: the code + applied migrations remain the source of truth.

## Async-capable work defaults to the queue

**Rule:** Work that can be performed asynchronously — third-party network I/O
(email/SES and the like) or any latency-unbounded side-effect — strongly
defaults to the queue and MUST NOT run inline on the request coroutine absent a
specific, documented reason it must (e.g. its result is required for the
response to be correct). The queue is the default; an inline async-capable path
carries the burden of justification.

**Why:** Inline third-party I/O binds a request's latency and success to a
dependency that is not part of its contract; a slow or failing provider then
degrades or fails a request that has otherwise succeeded. The queue provides the
retry, backoff, and timeout isolation the request path cannot.

## A required enqueue is part of the request transaction

**Rule:** When an enqueued side-effect is required (not best-effort), its
enqueue MUST occur inside the request's database transaction, and the request
MUST fail if the enqueue fails. Once the transaction commits, the request MUST
NOT wait on or depend on the job's execution.

**Why:** Enqueuing in-transaction makes the side-effect intent atomic with the
state that triggers it — no committed user without its verification-email job,
and none of that job without the committed user — and gives the enqueue the same
fate as the row it accompanies. Waiting on execution would reintroduce the
inline coupling the queue exists to remove; at-least-once delivery makes
completion the queue's responsibility, not the request's.
