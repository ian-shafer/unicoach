# RFCs

Numbered design documents (`NN-title.md`), one per feature, authored before the
code. They are the durable record of _why_ a change was made and _how_ it was
intended to work at the time it was written.

## Immutability — the core rule

**An RFC is committed once and is then immutable. NEVER edit an RFC that has
been implemented.** A committed RFC is historical record, not a living document.

This does not mean the design is frozen — designs evolve constantly. It means
the _mechanism_ for changing a decision is **a new, higher-numbered RFC that
supersedes the earlier one**, stating the supersession explicitly. The change is
then carried by:

- the **new RFC** (the record of the new decision), and
- the **living artifacts** it touches — the code, and the relevant `SPEC.md`.

Never reach back into an earlier RFC to revise it. If RFC 44 changes a contract
RFC 43 introduced, that change lives in RFC 44 ("supersedes RFC 43's …; the code
/ `SPEC.md` is the contract"); RFC 43's file is left exactly as committed.

The only freely-editable RFC is the one currently being authored and **not yet
committed** — the draft in its `/rfc-design` worktree.

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
