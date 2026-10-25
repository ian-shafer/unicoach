# Land

`ship-land` is the only way a /work run reaches `main`. It refuses a tip whose
tree the full hook never recorded, a mis-ordered `db/schema/ORDER`, a stale
coach-prompt seed, a dirty worktree and a non-fast-forward. Every land step runs
from `CODEBASE_ROOT` unless noted, all scripts through `nix develop -c`:

    $SHIP/ship-checkpoint -s <rs> before-land
    $SHIP/ship-lock   -s <rs> acquire       # waits while another run lands
    $SHIP/ship-rebase -s <rs>
    bin/prompt-seed -b "$BASE_SHA"          # only when prompts/ changed
    $SHIP/ship-order  -s <rs>
    $SHIP/ship-squash -s <rs>
    bin/format
    git add -A
    git commit -F <rs>/commit-msg.txt       # ONE commit, spec included, full hook
    $SHIP/ship-land   -s <rs>               # ff main, archive <rs>, remove worktree, release

Re-read `BASE_SHA` with `ship-state get` after the rebase; it moves.

Why this order:

- The lock brackets rebase through fast-forward: the hook's verdict is valid
  only for the base it ran on.
- `prompt-seed` and `ship-order` run after the final rebase, so they read the
  base as landed, and before the squash, so what they write lands inside the
  hook-verified tree. `ship-land` re-checks both dry.
- `bin/format` then `git add -A` re-stages what the formatter rewrote; without
  it the commit gets no verified marker and `ship-land` refuses after the gate.
- Finish `record.md` before `ship-land`: it archives `<rs>` to
  `.scratch/ship-archive/<run-id>/` and deletes the worktree. Append the landed
  SHA to the archived copy afterwards.

**The commit message**: a subject naming the change, a body of two or three
sentences on why, then the attribution line the session's instructions give.
Never `--no-verify`.

## When a step fails

**Release the lock first** (`ship-lock -s <rs> release`), then:

- **Rebase conflict**: decide which shape is right, resolve,
  `git rebase --continue`, record it. If the right answer is not obvious, abort
  and ask Ian.
- **Hook: tests red.** If this run caused it, fix from the failing lines,
  checkpoint, and restart land at `acquire`. If it is red at `BASE_SHA` too
  (check from the original checkout), stop and tell Ian with the failing lines
  verbatim and the log path; repairing it is a new run.
- **Hook: a shell harness red.** Environmental (missing `installDist`, a stopped
  daemon, a Postgres hiccup): fix the environment, change no code. This run's
  defect: fix, checkpoint, re-run that harness alone with
  `bin/shell-tests -n <name>`, then restart land. Pre-existing: as above.
- **Hook: format.** Run `bin/format`, `git add -A`, restart land.

Land always restarts at `ship-lock acquire`, never at `git commit`. A lock left
by a killed session expires by itself; `ship-lock -s <rs> release -f` is for
Ian.
