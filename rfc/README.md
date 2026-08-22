# RFCs

Numbered design documents (`NN-title.md`), one per feature, authored before the
code. They are the durable record of _why_ a change was made and _how_ it was
intended to work at the time it was written.

## Immutability

**RFCs are immutable after their initial commit, except for cosmetic updates
(e.g. formatting)** — see [`INVARIANTS.md`](INVARIANTS.md).

This does not mean the design is frozen — designs evolve constantly. It means
the _mechanism_ for changing a decision is **a new, higher-numbered RFC**: it
states the new decision and carries it into the **living artifacts** — the code
and, if a durable guarantee changes, the relevant `INVARIANTS.md`.

## RFCs are not the source of truth

An RFC defines the changes required at a point in time. RFC decisions are often
overridden by future RFCs. RFCs drive change in the codebase, but they are not
the primary source of truth for it: the files in the codebase — all code,
configuration, markdown, etc. — are the literal source of truth.

For "how does the system work _today_", precedence is: **code + applied
migrations (ground truth) > `INVARIANTS.md` (reviewed intent) > RFC
(point-in-time intent, often stale)**. When an RFC and the code disagree, the
code wins. Verify any schema/column/type/signature/constraint a design depends
on against the applied schema and live source — never against the RFC that
introduced it.

It is perfectly fine for an RFC to reference other RFCs when it adds value and
understanding. RFCs may also reference `SPEC.md` files; those files have been
subsequently removed — no different from an RFC referencing a code file that was
later deleted.

## Invariants

When a design introduces or changes a **durable guarantee** — a rule future code
must keep obeying that is not enforced by types, schema, or tests, and that
breaks something real if violated — the RFC declares it in an explicit
**Invariants** section: each entry names the target directory and gives the
**Rule** plus the **Why**. Most RFCs have no such section; most "musts" are not
invariants. The human reviews invariants as part of reviewing the RFC (this is
the human gate), and implementation (`/rfc-impl`) copies them into the target
directory's `INVARIANTS.md` alongside the code change — light editing to match
the file's style is fine; the meaning must not change.

## Artifacts

Supporting material that is not markdown — design mockups, screenshots,
diagrams, exported data — lives in **`rfc/artifacts/<NN>/`**, where `<NN>` is
the RFC's number. An RFC references its own artifacts by relative path, e.g.
`![login + onboarding](artifacts/116/iOS.jpg)`.

Two reasons the files live here rather than beside the thing they describe:

- **An RFC must stay readable on its own.** A mockup that a design was measured
  against is part of the record of _why_; if it is only in a chat message or a
  developer's `~/Documents`, the RFC's reasoning cannot be checked later. RFC
  116 exists partly because exactly that happened — `ios-app/DESIGN.md` cited a
  style reference that was never committed.
- **Artifacts inherit RFC immutability.** `rfc/artifacts/<NN>/` is written once,
  with the RFC. A superseding design gets a new number and a new directory; it
  never overwrites an earlier RFC's artifacts, because that would silently
  rewrite the evidence an already-committed document reasons from.

A **living** document (`ios-app/DESIGN.md`, say) is the opposite case and keeps
its assets next to itself, since both are expected to change together. If a
living doc needs the same image an RFC captured, it should reference the RFC's
copy by relative path rather than duplicate it — one file, one source of truth,
and the reference stays valid because the artifact is immutable.

## Lifecycle

RFCs are authored, reviewed, and implemented through the pipeline skills under
`.claude/skills/` (`rfc-design`, `rfc-design-review`, `rfc-impl`,
`rfc-impl-review`, …). Implementation leaves the RFC file untouched: it produces
code, tests, and — when the RFC declares invariants — the corresponding
`INVARIANTS.md` entries.
