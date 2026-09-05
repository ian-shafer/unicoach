# RFC 167: Serialize the ship land gate with a file lock

Instruction: Ian, 2026-09-06 — "Sessions are often trying to land /ship commits
and colliding. We should use bin/file-lock to gate commits by /ship. This will
prevent expensive test re-runs."

## Executive Summary

Parallel `/ship` runs are the normal case, and the skill already says so. What
it does not do is stop two runs from spending the expensive gate on the same
base. The sequence that costs real time is:

1. Run A and run B both fork at `main@X` and both pass
   `nix develop -c bin/test`.
2. A commits through the full `bin/pre-commit` hook and fast-forwards `main` to
   `Y`.
3. B commits through the full hook — also on `X` — and `ship-land` refuses:
   `[main] advanced past this run's base`. B rebases onto `Y`, and **runs the
   whole hook again**.

B paid for the gate twice. With three or four live worktrees, which is the
normal state of this repo, that is the dominant cost of landing.

**A lock around `git commit` alone does not fix this.** The collision is not two
`git commit` calls at the same instant; it is `main` moving while a run is
testing. The critical section is the whole final sequence — **rebase → squash →
format → commit (the hook) → fast-forward** — because the hook run is only valid
for the base it started on. Lock the commit and step 2 is still free to happen
during B's hook run.

So this RFC brackets phase 6 with one shared, expiring lock:

1. **`scripts/ship-lock`** — `acquire` / `release` / `status`, a thin, run-aware
   wrapper over `bin/file-lock` and `bin/wait-for`. It is a bracket, not a
   wrapper around the commands: phase 6 keeps its step-by-step shape, its own
   commit messages, and its ability to stop and wake Ian on a hook failure.

2. **One lock for the whole repo, derived from git.** `var/run/` is
   per-checkout, and every run works in its own worktree, so
   `$PROJECT_ROOT/var/run/ship-land.lock` would be a _different_ lock per run
   and would gate nothing. The lock is anchored at the main checkout, found via
   `git rev-parse --git-common-dir`, which every worktree agrees on by
   construction.

3. **`ship-land` self-heals.** It refuses a lock held by another run, acquires
   the lock itself when free, and releases a lock this run holds. Correctness —
   no interleaved fast-forward — therefore does not depend on the orchestrator
   remembering step 1. Acquiring early is what buys the saved test run;
   acquiring late still lands safely.

What this does not fix is stated plainly in §6: the Claude Code `rfc-pipeline`
and any hand-run `git commit` on `main` still bypass the lock, and serial gates
mean land _latency_ grows with the number of waiting runs even though total
machine time falls.

## Detailed Design

### 1. The critical section

    ship-lock acquire -s <rs>          # ← enter
    ship-rebase       -s <rs>
    ship-squash       -s <rs>
    nix develop -c bin/format
    nix develop -c git commit          # the gate: full bin/pre-commit
    nix develop -c git commit --no-verify   # RFC markdown, lane A
    ship-land         -s <rs>          # ff-merge, then releases  ← exit

`ship-rebase` is **inside** the lock and is the first thing after it. That is
the whole point: the run rebases onto a `main` that cannot move again until it
has landed, so the hook run that follows is valid at the moment of the
fast-forward. A rebase outside the lock is a rebase onto a tip that may already
be stale.

Phase 5's `nix develop -c bin/test` stays **outside** the lock. It is not the
gate — the hook re-runs it — and holding the lock through review and testing
would serialize the part of a run that has no reason to be serial.

### 2. `scripts/ship-lock`

    ship-lock [-s <run-scratch>] [-t <timeout>] [-f] acquire|release|status
    ship-lock acquire|release|status [-s <run-scratch>] [-t <timeout>] [-f]

`acquire` blocks until the lock is free, then holds it for at most 15m.
`release` drops it. `status` prints the holder and remaining time to stdout and
exits 0 **whether or not the lock is held** — it is for a human and for
`ship-status`, not a test. It still fails on a usage error or an unreadable
repo, and `ship-land` leans on exactly that to tell "free" from "could not
look".

**The operation name is the `RUN_ID`, and that is load-bearing.**
`bin/file-lock` exit code 3 means "already held by an operation matching `-o`",
and `daemon-up` treats it as success — correct there, because a concurrent
`daemon-up` is doing the work for you. A concurrent _land_ is not. If every run
passed `-o ship-land`, the second lander would get 3 and sail straight through
the lock into exactly the collision this RFC exists to prevent. With
`-o "$RUN_ID"` no two runs ever match, so a second lander gets 1 and waits.
`ship-lock acquire` therefore treats **0 as the only success from
`bin/file-lock`** and never re-reads 3 as `daemon-up` does.

What it does do is answer the self-held case itself, before `file-lock` is
called at all: **a lock this run already holds is a success, and the hold is
refreshed to a full 15m.** A compacted session is told to re-read `PHASE` from
disk and re-enter it, so a resumed run in `PHASE=landing` runs `acquire` a
second time by design; failing there would break this skill's own recovery path,
and it would fail while holding the very lock it refused to confirm. The refresh
is the load-bearing half — a resumed run can find 40s left on the hold, and a
bare success would hand it a lock about to be broken under its own gate. None of
this weakens the exit-3 rule: `-o` is the `RUN_ID`, so "matching op" means "me",
and one run has exactly one orchestrator. It is a fixed `-o ship-land` that
would make a match mean "somebody else".

**Durations, from a measurement.** `bin/file-lock`'s stale-breaking is
time-based, not liveness-based: at expiry the lock is taken from whoever holds
it, running or not. So the max duration is set from the real cost of the thing
it protects. A timed `nix develop -c bin/test check` in a fresh worktree on
2026-09-06 took **440s — 7m20s** (`BUILD SUCCESSFUL in 3m16s`, so roughly four
of those seven minutes are the non-Gradle part: DB reset and 36 migrations, the
two `installDist` builds, and `bin/shell-tests`).

The lock is therefore held for at most **15m** — double the measured gate — and
`acquire` waits up to **30m**, which is two full gates and so lets a run queue
behind two others rather than timing out into the collision it was avoiding.
Both are one number in `ship-lock`, and the measurement is the reason to revisit
them: if the gate crosses ~7m sustained, double the new figure.

An expiry that fires early is a **degradation, not a corruption**, which is what
makes a tight number safe to choose. If A's lock expires mid-gate and B takes
it, B rebases and lands; A then reaches `ship-land`, finds the lock held by a
foreign `RUN_ID`, and refuses (§4) — A re-runs its gate exactly as it does
today, with nothing wrongly landed. The expiry exists only so a killed session
cannot wedge every other run.

**Polling.** `bin/file-lock -t` delegates to `bin/wait-for` with its default 1s
period, which over an hour is 3600 attempts and 3600 progress dots. `ship-lock`
therefore calls `wait-for` itself with `-p 15s`, which is the right granularity
for a wait measured in minutes. `bin/file-lock` is left untouched: its own `-t`
path is tuned for `daemon-up`'s 16-second case and there is no reason to re-tune
it for this one.

The period is **clamped to the wait**: `wait-for` sleeps a whole period before
its second attempt, so `-t 1s` against a 15s period takes 15 seconds to report a
one-second timeout. A wait shorter than one poll is a single attempt. This is
not a micro-optimisation — the harness asks for exactly that shape when it
asserts a blocked run, and without the clamp the pre-commit gate grows 15
seconds of pure sleep.

### 3. Where the lock lives

    LOCK_ROOT="$(dirname "$(git -C "$ANCHOR" rev-parse --path-format=absolute --git-common-dir)")"
    LOCK_DIR="$LOCK_ROOT/var/run/ship-land.lock"

`--git-common-dir` resolves to the main checkout's `.git` from **any** worktree
of this repo, so its parent is the main checkout, so every run — `/ship`
worktrees under `/Users/ian/Work/unicoach-rfc-<n>`, and the Claude Code
worktrees under `.claude/worktrees/` — computes the same path without being told
it. `var/` is already gitignored, and `var/run/` is where `bin/file-lock` locks
live by existing convention (`daemon-up`). Deriving the path from git rather
than from `ORIGINAL_CHECKOUT` also means `rfc-pipeline` can adopt the same lock
later with no coordination beyond calling the same three lines.

`git -C` is stripped of its environment first: **`GIT_DIR` outranks `-C`**, and
a git hook exports `GIT_DIR`. Since the harness for this lock runs inside
`bin/pre-commit`, the un-stripped form walked out of its throwaway `git init`
fixture and took the real `/Users/ian/Work/unicoach/var/run/ship-land.lock` — a
test that gated every live `/ship` run on the machine for 15 minutes. This was
found by the gate itself, on the first commit attempt, which is the one place it
could have been found. `env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE` makes
the argument the answer, and an assertion holds it.

`ANCHOR` is the run's own `CODEBASE_ROOT`, falling back to `ORIGINAL_CHECKOUT`
and then to the checkout the script itself lives in. Every one of those is a
worktree of the same repository and git answers identically for all of them, so
the fallback chain is about _existence_, not about which answer is right: the
worktree is gone once `ship-land` has removed it. Taking the run's own repo
first is also what makes the harness possible — pointing a fabricated run state
at a throwaway `git init` repo is what keeps the tests off the real lock, with
no test-only flag to be wrong about.

### 4. `ship-land` becomes the backstop

`ship-land` is already documented as "the ONLY path by which a ship run reaches
the base branch", and it already refuses a non-fast-forward. Three additions
make the lock hold even when phase 6 is interrupted mid-way by a compaction:

- **Refuse a foreign holder.** If the lock is held by another `RUN_ID`, fail
  before the fast-forward, naming the holder. A run that got here without the
  lock has a hook result validated against a base another run is about to move.
- **Acquire, always.** With a short timeout (60s, not 30m — the gate is already
  spent by this point, so a long wait here buys nothing and a foreign holder is
  a refusal, not a queue). Unconditionally rather than only-when-free, because
  `acquire` is idempotent and **refreshes** the hold: phase 6 is agent-driven,
  so the wall clock from its `acquire` to here includes turnaround and not only
  the 440s gate, and a hold that lapses during the assert and the fast-forward
  lets another run in. It also removes the read-then-acquire window.
- **Release what this run holds** — on every fatal after acquisition via `trap`,
  and on the success path **explicitly**, immediately after the fast-forward and
  its post-land verify. The success release cannot be left to the trap: the run
  scratch `ship-lock` reads its `RUN_ID` from lives inside the worktree that
  teardown removes, so by the time an `EXIT` trap fired there would be no state
  file to read. Releasing at the fast-forward is also honest about where the
  critical section really ends — everything after it is this run's own teardown
  and moves nothing another run reads. Never release a lock held by another run;
  `-f` exists for a human, not for a script.

Because `ship-land` is the last step, the happy path never leaks the lock, and
no other script needs a `trap`.

### 5. Releasing on the unhappy path

The one place after approval where the skill wakes Ian is a failing hook — and
that happens **inside** the critical section. A run that stops to ask a human
while holding the lock blocks every other run for up to 15 minutes, which is the
cost this RFC set out to remove.

So the rule in SKILL.md is explicit: **any exit from phase 6 that is not a land
releases the lock first.** Report to Ian after `ship-lock release`, not before.
The fix loop then re-acquires when it is ready to try again.

`ship-status` grows one line — the current holder and its remaining time — so a
resumed or blocked session sees a stuck lock without knowing to look for it. A
human breaks one with `ship-lock release -f`, which logs a warning naming the
holder it displaced.

### 6. What this does not fix

- **Non-`/ship` landers.** `rfc-pipeline` and a hand-run `git commit` on `main`
  do not take the lock. `ship-land`'s existing fast-forward refusal remains the
  backstop for them, and the wasted-hook-run case remains possible against
  those. This is a net win, not a guarantee.
- **Throughput.** Gates become strictly serial, so worst-case land latency is
  now (waiting runs × gate duration). The total machine time still falls — a
  wait costs the same wall clock as the duplicate hook run it replaces, and the
  waiting session does not burn CPU — but a run can now sit idle for a long
  time, visibly, where it used to fail fast and re-run.
- **The verify-then-gate double test.** A run still runs `bin/test` at verify
  and again in the hook. Gradle's cache makes the second cheap when nothing
  moved; a rebase inside the lock invalidates it. That is inherent to
  rebase-then-gate, not something a lock can remove.

## Files Modified

| File                                           | Change                                                                                           |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `.prime/agent/skills/ship/scripts/ship-lock`   | **New.** `acquire`/`release`/`status` over `bin/file-lock` + `bin/wait-for`; `-o "$RUN_ID"`.     |
| `.prime/agent/skills/ship/scripts/ship-land`   | Refuse a foreign holder; acquire when free (60s); `trap`-release a lock this run holds.          |
| `.prime/agent/skills/ship/scripts/ship-status` | Print the lock holder and its remaining time.                                                    |
| `.prime/agent/skills/ship/SKILL.md`            | Phase 6 brackets with `ship-lock`; the release-before-waking-Ian rule; script table gains a row. |
| `bin/ship-scripts-tests`                       | **New.** Shell assertions over `ship-lock`.                                                      |
| `bin/shell-tests`                              | Add `ship-scripts-tests` to the aggregator.                                                      |
| `bin/test`, `README.md`, `CLAUDE.md`           | The harness roster, in the three places outside `bin/shell-tests` that enumerate it.             |

No Kotlin, no schema, no migration.

## Implementation Plan

1. **`ship-lock`.** `getopts` for `-s`/`-t`/`-f`/`-h`; subcommand as the single
   positional. Derive `LOCK_DIR` from `--git-common-dir` (§3). `acquire` calls
   `bin/wait-for -p 15s "$TIMEOUT" bin/file-lock -o "$RUN_ID" "$LOCK_DIR" 15m`
   with `TIMEOUT` defaulting to `30m`, and maps exit 3 to a clear internal
   error. `release` reads `$LOCK_DIR/op`, refuses a foreign holder without `-f`,
   and `rm -rf`s the directory. All logging to stderr; `status` prints its
   report to stdout.
2. **`ship-land`.** Insert the holder check before the `ship-verified` assert
   (fail early, before the expensive part), acquire-if-free after it, and a
   `trap` that releases only a lock whose `op` is this `RUN_ID`.
3. **`ship-status`.** One extra line from `ship-lock status`.
4. **SKILL.md.** Phase 6 sequence, the release-on-unhappy-path rule, the script
   table row, and one paragraph in "Parallel runs" naming the lock as the reason
   a land may now wait.
5. **`bin/ship-scripts-tests`** and its `bin/shell-tests` entry — plus the
   harness roster wherever else it is written out: `bin/test`'s two strings,
   `README.md`'s list of harnesses to run directly, and `CLAUDE.md`'s
   `### check also runs the shell harnesses`. A roster that is stale in the
   document a future agent reads first is worse than no roster.

**Sequencing note.** This run edits the very skill that is driving it. That is
safe here and only here: the orchestrator invokes `scripts/ship-*` from the
**original checkout**, while the run's edits land in its own worktree copy, so
the new `ship-lock` does not become live until after this RFC has landed. The
phase-4 deny list must therefore drop its usual
`-d '.prime/agent/skills/ship/*'` entry, and `.claude/*` stays denied.

## Tests

`bin/ship-scripts-tests`, a new `assert_success`/`assert_failure` harness in the
existing `bin/scripts-tests` idiom, run by `bin/shell-tests` and therefore by
the `bin/test check` gate. It exercises `ship-lock` against a temporary
`LOCK_DIR` and a fabricated run-scratch `state` file — no worktree, no Gradle:

1. `acquire` on a free lock exits 0 and writes `op` = `RUN_ID`.
2. A **second, different** `RUN_ID` blocks: `acquire -t 1s` exits non-zero and
   does not overwrite `op`. This is the exit-code-3 trap from §2, and it is the
   single most important assertion in the file. Its twin: the **same** `RUN_ID`
   re-acquiring exits 0 **and** pushes `expires-at` back out to the full hold —
   the resumed-session path. A test that only asserted the exit code would pass
   on a bare success that left the run holding a lock with seconds left.
3. `release` by the holder removes the lock; a subsequent `acquire` by another
   run succeeds.
4. `release` by a **non-holder** fails and leaves the lock intact; `release -f`
   succeeds and warns.
5. An expired lock is broken and re-acquired (`expires-at` in the past).
6. `status` on a free lock exits 0 and says so; on a held lock it names the
   holder.
7. Usage errors: unknown option, `-s` without a value, an unknown subcommand, a
   missing `-s` — each with the reserved `bin/functions` exit code.
8. `-h` exits 0 and prints to **stdout**; a normal `acquire`/`release` writes
   **nothing** to stdout (the `bin/` logging invariant).

One assertion is deliberately probabilistic, and says so where it lives.
`bin/file-lock` breaks a stale lock with `mv` then `mkdir`, which is not atomic,
so two runs whose expiry reads both predate the other's break can **both** be
told they acquired it. `daemon-up` never cared — a matching op is success there
— but RFC 167 does, and it makes the expired state routine rather than
exceptional, because a queue of waiters converges on the instant a hold lapses.
`ship-lock acquire` therefore **confirms it is the recorded holder** after
`file-lock` returns 0 and refuses if it is not: one read, and it can only fail
safe. The harness races eight runs at a released `mkfifo` gate against an
expired lock and demands exactly one winner, twice. With the confirmation
removed the race reproduces in roughly a quarter of trials; with it in place it
never has. Two trials is a net, not a proof — sized against the ~6s it adds to
every pre-commit run.

`ship-land`'s new behaviour is covered by the same harness at the level it can
be: with another run holding the lock, `ship-land` refuses and names the holder,
and it does so before any git write. The fast-forward path itself stays
uncovered by shell tests, as it is today — it needs a real worktree, and this
run exercises it by landing itself.
