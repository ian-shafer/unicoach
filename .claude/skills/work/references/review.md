# Review

One rule, one reviewer. The `code-review-*`, `design-review-*` and
`impl-review-*` skills look overlapping and are not: their narrow scope is what
makes a single-rule reviewer accurate, and a generalist applying all of them
under-finds (`code-review-chain`). Filter the lenses that cannot fire; never
merge them. The rules are edited only by `/principle`.

## Tier order

`.claude/skills/review-fix/tiers.md` is the one source of order: each lens sits
in the tier matching the blast radius of the fix its findings induce.

| Tier | Shape                   | Note                                                  |
| ---- | ----------------------- | ----------------------------------------------------- |
| 0    | spec conformance        | the only tier that may conclude **the spec is wrong** |
| 1    | multi-file / structural |                                                       |
| 2    | single-file             |                                                       |
| 3    | 1–4 lines               | renames, wording, comments                            |

Tiers run one after another, and each sees the tree the previous tier's fixes
left, so a structural fix can delete the line a nit was about before anyone
fixes the nit. Lenses within a tier run in parallel. Tier 0 runs at spec 2–4.

## One tier

1. **Materialize the context once**: write `<rs>/findings/tier<N>-context.md`
   holding `git diff <BASE_SHA>` from `CODEBASE_ROOT`, then the full contents of
   each changed non-test file, and at Tier 0 the spec. Every reviewer in the
   tier reads this one file.
2. **The dispatch set**: `nix develop -c $SHIP/ship-lens -s <rs> missing -t <N>`
   lists every lens the tier assigns. Skip the ones the applicability table in
   [`dials.md`](dials.md) rules out:
   `ship-lens skip -t <N> -l <lens> -r <reason>`.
3. **Dispatch, all in one message**, one background `Agent` per remaining lens:
   `code-reviewer` for `code-review-*`, `design-reviewer` for `design-review-*`,
   `general-purpose` with `model: sonnet` for `impl-review-*`. Record each with
   `ship-lens start -t <N> -l <lens> -c <agent-id>` as it is spawned. The
   prompt:

   ```text
   SKILL: <lens>
   Invoke the Skill tool with exactly that name first; judge against its loaded text.
   Review context: <rs>/findings/tier<N>-context.md (the diff, changed files, and at Tier 0 the spec).
   Open further files ONLY when your rule genuinely requires context beyond it, and say how many you opened.
   Read-only: never edit the code tree, never run tests or start services.
   Write your report to <rs>/findings/tier<N>-<lens>.md.
   <the output instruction below, verbatim>
   Reply with one tab-separated line: lens, grade, finding count, files opened.
   ```

4. **Close**: as each reports, `ship-lens done`. The tier closes only when
   `ship-lens missing -t <N>` exits 0. A stalled or malformed reviewer is
   re-dispatched once with the reason; a second failure is skipped with the real
   reason. Never grade a report yourself.
5. **Fix**: if the tier has anything to fix, one fix subagent (below). Nothing
   to fix spawns nothing.
6. Record the tier's counts in `record.md`, then start the next tier.

## The output instruction, verbatim

```text
Output per `.claude/skills/findings-output.template.md`, PLUS: directly under
`**Verdict:**` emit a line

    **Grade:** CLEAN | ADVISORY | SHOULD-FIX | BLOCKING

and end EVERY finding heading with its own tag in brackets, e.g.

    ### The plan step names a file that does not exist [SHOULD-FIX]

ADVISORY   = a preference; leaving it is not a defect (naming, wording, a "consider").
SHOULD-FIX = a corpus rule is broken, or a contained defect. Fix before land.
BLOCKING   = correctness, safety or data loss; or (Tier 0 only) the code is right
             and the spec is wrong.

The report's Grade is the highest tag it carries.

AND: a real defect you see that your own rule does not cover goes under a final
heading

    ## Out of lens

one tagged `###` finding each, with its `file:line`, what it is, and its own
grade tag. Report it there; do not fix it, and do not judge whose rule it is.
```

A `🔴 FAIL` with no tags is malformed.

## What gets fixed

| Grade        | `targeted`     | `full`   |
| ------------ | -------------- | -------- |
| `BLOCKING`   | fixed          | fixed    |
| `SHOULD-FIX` | held, reported | fixed    |
| `ADVISORY`   | reported       | reported |

Out-of-lens findings are fixed by their grade **only within this run's own
diff**. A defect in code the run did not touch is an open item in the report
with its `file:line`; fixing it would land something Ian did not approve.

## The fix subagent

One per tier, on the main model — two writers in one worktree race. Its brief:
the findings to apply, inline, each with its report path; `CODEBASE_ROOT`; apply
Option 1 unless it no longer fits the tree, saying why; run
`nix develop -c bin/test` once after the batch and report counts;
`$SHIP/ship-checkpoint -s <rs> tier<N>-fix`; reply with one line per finding:
`ACCEPTED`, `DECLINED <why>`, or `CONTRADICTION`.

**Contradictions.** A finding that cannot be applied without breaking another
rule the run is bound by — another lens, a `CLAUDE.md` convention, the approved
spec, or an earlier tier's applied fix. Synthesis first: one change satisfying
both is `ACCEPTED`. Otherwise that item halts, not the batch: the fix report
quotes both rules verbatim with their paths and says why no change satisfies
both. A contradicted `SHOULD-FIX` is recorded and left; a contradicted
`BLOCKING` goes to Ian. A later tier never silently reverts an earlier tier's
fix.

**Tier 0 and approved text.** Amending the spec to match the code is normal Tier
0 work — the code wins (`rfc/README.md`). When the line is one Ian approved, the
fix subagent leaves it and reports it; the coordinator takes it to Ian.
