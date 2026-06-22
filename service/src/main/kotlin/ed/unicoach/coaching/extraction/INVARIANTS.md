# INVARIANTS — coaching/extraction

The per-conversation extraction pass: distills finished coaching turns into
immutable observations and revisable claims. Runs only in the queue worker,
across a read transaction, an LLM call outside any transaction, and a write
transaction.

## Invariants

### The LLM call holds neither a connection nor the student lock

**Rule:** The `ChatProvider.chat()` call MUST run outside any
`database.withConnection` block — between the read transaction and the write
transaction — never inside one. The student advisory lock
(`AdvisoryLockDao.lockStudent`) MUST be taken inside each short transaction and
released at its commit, NEVER held across the LLM call.

**Why:** The provider call takes seconds. Holding a pooled connection across it
starves the Hikari pool; holding `pg_advisory_xact_lock` across it serializes
every other pass for that student behind one slow network call, collapsing the
per-student concurrency the design depends on. Collapsing the three phases into
one transaction reintroduces exactly the pin the split exists to avoid.

### Same-student passes serialize on the advisory lock, not on row versioning

**Rule:** Every extraction pass that mutates a student's claims MUST hold the
student advisory lock (`AdvisoryLockDao.lockStudent`) across the claim read-back
and the claim writes. `claims` carries no `version`/optimistic-concurrency
column; the advisory lock is the sole concurrency control for same-student claim
state.

**Why:** Two passes for the same student — across different conversations — race
on the same claim rows: supersede, reinforce, and the confidence recompute each
read-modify-write `claims`/`claim_support`. With no OCC column to reject a stale
write, a parallel unlocked pass silently loses confidence updates and can
corrupt claim lifecycle (e.g. superseding a claim a concurrent pass already
retracted). The lock is a runtime discipline, not a type- or DB-enforced
guarantee, so a refactor can silently drop it.

### The watermark is re-read under the write lock before any persistence

**Rule:** The write transaction MUST re-acquire the student lock and re-read the
conversation watermark, and MUST no-op (return `Success`, write nothing) when a
concurrent same-conversation pass has already advanced it past the target.
Supersede/reinforce ops MUST validate against the active-claim set **re-loaded
inside the write transaction**, not the read-phase snapshot.

**Why:** Job delivery is at-least-once and the LLM window is lock-free, so two
passes over the same conversation can both pass the read-phase check. Derived
rows (observations, claims, runs) carry no unique constraint, so the write-time
re-check is the _only_ thing that makes incremental extraction idempotent;
dropping it duplicates observations and double-advances the watermark.
Validating a stale active set against an interleaved supersede corrupts claim
lifecycle state.

### Confidence is computed in code, never taken from the LLM

**Rule:** `claims.confidence` MUST be computed by `computeConfidence` from the
claim's `claim_support` set at pass time. The parsed LLM output MUST NOT carry
or set a confidence value; the prompt proposes structure (observations, claim
ops), code assigns the score.

**Why:** Confidence is a reproducible function of recurrence and recency
(`1000·(1−exp(−Σwᵢ))` with half-life decay) used to rank beliefs. Letting the
LLM assign it makes the score an unreproducible model whim, decoupled from the
actual evidence and silently un-decayed over time — defeating the recency model
and making confidence non-comparable across passes.

## History

- [x] [RFC-66: Extraction](../../../../../../../../rfc/66-extraction.md)
