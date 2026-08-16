# Review: dispatching the shared rule corpus

## Where the rules live (read-only)

    .claude/skills/impl-review-*/SKILL.md      Tier 0 — RFC conformance
    .claude/skills/code-review-*/SKILL.md      code lenses
    .claude/skills/design-review-*/SKILL.md    design lenses
    .claude/skills/review-fix/tiers.md         the ordering manifest
    .claude/skills/findings-output.template.md the output contract

`ship` **reads** these and never writes them. The `*-chain` and `review-fix`
skills beside them are Claude Code orchestration (Agent tool, depth-1 fan-out,
worktree-per-fix) and are deliberately not used — this skill dispatches the same
rules with its own machinery.

## Discovery and the accounting invariant

Glob the three patterns, read `tiers.md`, and **assert every discovered rule
appears in the manifest**. This is the same assertion `/review-fix` makes, and
it exists because the dangerous failure is silent: a review that quietly runs
with fewer lenses looks exactly like a clean one.

Halt loudly if discovery returns zero rules, if a discovered rule is absent from
`tiers.md`, or if the paths above do not exist. One clear error beats a no-op
review.

## Do not merge the rules

The rules look overlapping and are not. `design-review-minimum-context` is about
implicit string-parsing contracts and global memory, not decomposition.
`design-review-scale-restraints` is narrowly about load engineering at 1 QPS,
not general YAGNI. `code-review-concrete-names` (drop filler) and
`code-review-no-smurf-naming` (drop redundant type info) push the same
direction, not opposite ones.

The narrow scoping is engineered — `no-smurf-naming` carries a medium-relative
exception and a carve-out for `<Operation><Entity>Result`, which are the
fingerprints of a rule sharpened against real false positives. That precision is
what makes a single-lens adversarial reviewer accurate, and `code-review-chain`
records that a generalist applying all lenses at once **under-finds**.

**One rule, one reviewer.** If cost bites, skip lenses that cannot fire on the
changed file types — filter, never merge.

## Tier order is load-bearing

From `tiers.md`: every rule is assigned a tier by **the blast radius of the fix
its findings induce**, not by importance. Tiers are triaged in order so a
structural finding is decided before the nits its fix would delete.

| Tier | Shape                   | Note                                                         |
| ---- | ----------------------- | ------------------------------------------------------------ |
| 0    | RFC conformance         | The only tier whose triage may conclude **the RFC is wrong** |
| 1    | multi-file / structural |                                                              |
| 2    | single-file             |                                                              |
| 3    | 1–4 lines               | Renames, wording, comments                                   |

Run tiers **sequentially**; the tier base moves as each tier's accepted fixes
integrate, so Tier 2 must see the tree Tier 1 left. Parallelise within a tier.

Tier 0 findings are special: prefer amending the code, but when the
implementation is right and the RFC was wrong, say so — that is the
reconciliation point between intent and reality, and per `rfc/README.md` the
code wins.

## Applicability filter

Skip a lens whose subject cannot appear in the change. A bash-only change does
not need `code-review-structured-payloads`; a markdown-only change needs almost
none. Record which lenses were skipped and why in the run's findings directory —
a skipped lens must be visible in the report, never silent.
