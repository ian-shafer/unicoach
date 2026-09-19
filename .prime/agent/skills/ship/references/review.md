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

State the reason **once** for the whole skipped set when it is one reason, e.g.
`{"reason": "...", "lenses": [...]}`, rather than copying the same sentence onto
every entry. One shared justification is easier to challenge than twenty
identical ones, and a lens that needs its own reason is exactly the one worth
reading.

## Dispatch output contract

`.claude/skills/findings-output.template.md` is the output contract, and it says
a caller's own output instructions **win**. This skill uses that clause; it does
not edit the template, and it does not edit a lens. Every lens dispatch adds one
instruction on top of the template (RFC 233).

Directly under `**Verdict:**`, one line:

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and every finding heading ends with its own tag in brackets —
`### The [n] marker is formatted twice [SHOULD-FIX]`. The report's grade is the
highest tag it carries. `**Verdict:**` is unchanged, so anything reading only
the verdict sees exactly what it saw before.

| Grade        | Means                                                                                      | The run then       |
| ------------ | ------------------------------------------------------------------------------------------ | ------------------ |
| `CLEAN`      | no findings (`🟢 PASS`), or the lens does not apply (`⚪ N/A`)                             | nothing            |
| `ADVISORY`   | a preference — a name, a phrasing, a comment, a "consider". Leaving it is not a defect.    | reports, unapplied |
| `SHOULD-FIX` | a corpus rule is broken, or a defect whose fix is contained. Fix before land.              | fix batch          |
| `BLOCKING`   | correctness, safety or data loss; or, Tier 0 only, the code is right and the RFC is wrong. | fix batch          |

The grade exists because the verdict cannot steer the spend: any finding at all
makes a report `🔴 FAIL`, and `FAIL` is what dispatches a fix child — the unit
that costs several times a lens. RFC 233 carries the measurement; it is not
repeated here, because a figure in a file edited this often goes stale silently
and an RFC is immutable. The lens already knows the difference ("one should-fix,
one consider"); the grade is it saying so where the caller can read it.

### The instruction, verbatim

This is the text a dispatch appends, and the text a re-dispatch quotes. It lives
here because a rule that says "quote the instruction" must name something that
still exists after the run's scratch directory is deleted.

```text
Output per `.claude/skills/findings-output.template.md`, PLUS: directly under
`**Verdict:**` emit a line

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and end EVERY finding heading with its own tag in brackets, e.g.

    ### The plan step names a file that does not exist [SHOULD-FIX]

ADVISORY   = a preference; leaving it is not a defect (naming, wording, a "consider").
SHOULD-FIX = a corpus rule is broken, or a contained defect. Fix before land.
BLOCKING   = correctness, safety or data loss; or (Tier 0 only) the code is right
             and the RFC is wrong.

The report's Grade is the highest tag it carries.
```

**A `🔴 FAIL` with no tags is malformed.** Re-dispatch that one lens with the
instruction quoted. Never infer the grade — inferring it is the caller grading
the code, which is the thing one-rule-one-reviewer exists to prevent.

### What gets a fix child

`SHOULD-FIX` and `BLOCKING` findings only. A tier whose reports are all `CLEAN`
or `ADVISORY` spawns no fix child and closes; the next tier runs against the
same tree, which it would have anyway.

The tier's fix plan (`<rs>/findings/tier<N>-fix-plan.md`) records three sets:
dispatched, held as advisory, and **promoted or demoted with a one-line
reason**. The coordinator may overrule a tag in either direction — that is a
judgement call it is entitled to make — but a silent demotion is how a real
defect becomes an unread line, so both directions are written down.

Held findings reach Ian in the phase 7 report under **Advisory findings (not
applied)**, one line each: lens, `file:line`, title. `None` is a real result.

### Tier 0 may not silently rewrite an approved decision

Tier 0's characteristic outcome is "the code and the RFC disagree", and per
`rfc/README.md` the code wins — the run amends the RFC and carries on. That is
unchanged, and it is most of them.

The exception is narrow: when the RFC line to be amended is one **Ian approved
at the gate**, the run is editing the agreement rather than correcting a stale
sentence. Stop and ask, in the `YOUR DECISION REQUIRED` block defined in
[`../../shared/operator-comms.md`](../../shared/operator-comms.md), quoting the
approved text and what the code does. The default taken in silence is to amend
the RFC to match the code.

This is not an ordering rule. Tier 0 already resolves before Tier 1 — the tiers
are sequential and each tier's fixes move the tree the next one reviews. It is
only the escalation.
