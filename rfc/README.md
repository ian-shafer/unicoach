# RFCs

Numbered design documents (`NN-title.md`), one per feature, authored before the
code. They are the durable record of _why_ a change was made and _how_ it was
intended to work at the time it was written.

## Immutability — the core rule

**An RFC is committed once and is then immutable. NEVER edit an RFC that has
been implemented.** A committed RFC is historical record, not a living document.

This does not mean the design is frozen — designs evolve constantly. It means
the _mechanism_ for changing a decision is **a new, higher-numbered RFC**: it
states the new decision and carries it into the **living artifacts** — the code
and the relevant `SPEC.md`. Never reach back into an earlier RFC to revise it.

The only freely-editable RFC is the one currently being authored and **not yet
committed** — the draft in its `/rfc-design` worktree.

## Reference the source of truth, not other RFCs

An RFC's prose references the **`SPEC.md` and the code** — never another RFC.
This holds even when the RFC changes an earlier decision: state the change
against the contract _as it lives in the code / `SPEC.md`_ (e.g. "extends the
closed `ChatEvent` taxonomy in `ChatEvent.kt`"), not as "supersedes RFC NN." A
committed RFC is a point-in-time record that may already be stale, so citing one
as authority points at the wrong thing — the SPEC and code are what an
implementer must match. For the precedent or structure a new adapter mirrors,
cite the existing module's code and `SPEC.md`, not the RFC that introduced it.

## Source of truth

For "how does the system work _today_", precedence is:

**code + applied migrations (ground truth) > `SPEC.md` (curated, kept in sync) >
RFC (point-in-time intent, often stale).**

When an RFC and the code disagree, the code wins. Verify any schema/column/
type/signature/constraint a design depends on against the applied schema and
live source — never against the RFC that introduced it.

## Lifecycle

RFCs are authored, reviewed, implemented, and spec-synced through the pipeline
skills under `.claude/skills/` (`rfc-design`, `rfc-design-review`, `rfc-impl`,
`rfc-impl-review`, `spec-writer`, …). Implementation leaves the RFC file
untouched: it produces code and tests, and `SPEC.md` files are synchronized in a
separate phase. The RFC itself is never modified after it is committed.
