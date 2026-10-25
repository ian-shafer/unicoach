# The implementer brief

One `general-purpose` subagent on the main model writes the change. It works in
`CODEBASE_ROOT` only and makes no commits of its own beyond checkpoints. Its
brief carries, in order:

1. **The instruction**, verbatim, and the spec: the RFC path (spec 3–4), the
   plan (spec 2), or "the instruction is the spec" (0–1).
2. **What Ian approved**, from `<rs>/approval.md`, when it exists.
3. **Where to work**: `CODEBASE_ROOT`, never the original checkout.
4. **Tests first** at spec 1: write the failing test, run it, see it fail for
   the stated reason, then fix. At every level, a behaviour change carries a
   test that would have failed without it. Prefer real collaborators (a real
   database) over fakes; never assert on source text.
5. **The suite**: `nix develop -c bin/test` unscoped, output to
   `<rs>/runs/impl-suite.log`, reading back only the summary and failing lines.
   Report the executed and failed counts. Never `-f`, never a module scope as
   the final check.
6. **Checkpoints**: `$SHIP/ship-checkpoint -s <rs> impl <i> -m <what>` after
   each coherent step, so a stall costs a step, not the run.
7. **The deny list**: never write `.claude/skills/work/**`,
   `.prime/agent/skills/ship/**`, or another run's worktree. The coordinator
   checks it afterwards with
   `$SHIP/ship-verify-scope -s <rs> -f <BASE_SHA> -d '.claude/skills/work/*' -d '.prime/agent/skills/ship/*'`.
8. **Irreversible questions stop it.** A choice about schema, stored data shape,
   a prompt or public copy that the spec does not settle: write it to
   `<rs>/decision.md` with a recommended default and stop. The coordinator takes
   it to Ian and resumes the implementer with the answer.
9. **Coach prompt changes** edit `prompts/coach-system-prompt.md` only, one
   sentence per line; `nix develop -c bin/prompt-seed -b <BASE_SHA>` writes the
   seed migration, its `ORDER` line and the version pin. Never hand-write them.
10. **The reply**: what changed (files, one line each), the suite counts, any
    reversible choices made with their reasons, anything left undone.

When the implementer replies, the coordinator reads the diff itself before
moving on. A reply is a report, not proof.
