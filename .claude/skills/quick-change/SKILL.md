---
name: quick-change
description: >-
  Lightweight end-to-end process for small, low-risk code changes: implement in
  a dedicated worktree branched off main, review the diff, then commit and
  fast-forward merge back into main. The quick sibling of rfc-pipeline — no RFC
  document, no fan-out review, no background agents. Use when a user asks for a
  "quick change", "small fix", "quick patch", or invokes /quick-change.
---

# Quick Change

Runs one small code change through a complete worktree → implement → review →
land cycle. The work happens in a **dedicated throwaway git worktree** on a
`quick/<slug>` branch, so it never shares a HEAD with the original checkout — a
concurrent `/rfc-pipeline` (or anything else operating in the original checkout)
cannot `git checkout` out from under it and strand the commit on the wrong
branch. Everything runs inline in this session; no background agents, no RFC.

## Invocation Parameters

- **Change description** (required): what to change. If the user invoked the
  skill without saying what to change, ask before doing anything else.
- **Review depth** (optional): `light` (default) or `thorough`. `light` runs the
  built-in `/code-review` skill at medium effort on the diff. `thorough` runs
  the full `code-review-chain` fan-out — only when the user explicitly asks; if
  they wanted heavyweight they'd usually be in `/rfc-pipeline`.

Whether a change is appropriate for this process (versus `/rfc-pipeline`) is the
**user's judgment call** — do not screen or second-guess the request; start
immediately.

## Phase 1 — Worktree

All git/toolchain commands run via `nix develop -c` per CLAUDE.md. The
**original checkout** is the directory this session started in; it is **left
untouched** during the work — no `git switch`, no clean-tree requirement —
because the change is built in its own worktree, off the committed `main` ref.

1. Claim a short kebab-case `<slug>` summarizing the change (e.g.
   `fix-login-typo`).
2. Create the worktree and its branch off `main`, run from the original
   checkout:

   ```sh
   nix develop -c git worktree add -b quick/<slug> ../unicoach-quick-<slug> main
   ```

   Resolve `../unicoach-quick-<slug>` to an absolute path and record it as
   `<worktree>`. If that path or the `quick/<slug>` branch already exists (a
   prior quick-change), pick a distinct slug. **All of Phases 2–3 run inside
   `<worktree>`** (`cd` there, or `git -C "<worktree>"`); the original checkout
   is not modified until land.

## Phase 2 — Implement

1. Make the change, honoring the baseline skills (`coding`, `kotlin-coding`,
   `general-design`, `shell-scripts` — whichever apply to the touched files).
2. Read every touched directory's **INVARIANTS.md** and keep its rules true —
   but do **not** edit the file itself; it is human-gated. If the change affects
   an invariant, tell the user rather than editing it. Never create a SPEC.md —
   this codebase does not use them.
3. Run the affected tests through the harness, forcing a real run:
   `nix develop -c bin/test <module> -f`. Confirm tests actually executed ("N
   executed", not an all-cache no-op). For non-Kotlin changes (docs, scripts),
   run whatever check exercises them (`deno fmt --check`, executing the script's
   own tests, etc.).

## Phase 3 — Review

1. Invoke the `code-review` skill on the current diff (medium effort for
   `light`; for `thorough`, invoke `code-review-chain` instead — inline in this
   session, never from a background agent, per its depth-1 invariant).
2. Fix confirmed findings, re-run the affected tests, and re-review only if the
   fixes were more than mechanical.
3. Findings that are real but out of scope for a quick change: flag them
   (spawn_task or a note to the user), do not grow the diff.

## Phase 4 — Land

1. Present to the user in one message: the list of changed files, a short
   summary of the diff, the review outcome, and the proposed commit message
   (imperative subject ≤ 50 chars, blank line, wrapped body — per
   `git-commits`). **Wait for approval** — this is the single confirmation gate
   of the whole flow. The user may pre-authorize it ("land it without asking")
   in the invocation, in which case proceed directly.
2. Commit **inside the worktree** through the full gate — no `--no-verify`, the
   checkpoint carve-out does not apply here:

   ```sh
   cd "<worktree>"
   nix develop -c git add <explicit files>
   nix develop -c git commit ...
   ```

3. **Rebase onto the current `main` — expect this, do not treat it as an
   exception.** `main` is a moving target: it may have received commits (another
   session, a landed `/rfc-pipeline`, a teammate) between Phase 1 cutting the
   branch and now, so by land time the branch is very often _behind_ `main`.
   Rebase the worktree branch onto the current `main` and re-run the affected
   tests in the worktree, so the land below is a true fast-forward and not a
   divergent-history merge:

   ```sh
   nix develop -c git -C "<worktree>" rebase main
   ```

   (`main` is a shared ref across worktrees, so this rebases onto whatever
   `main` currently points at — no fetch needed for local-only work.) If the
   rebase conflicts, stop and report — the worktree is left intact; do not force
   anything.
4. Land and tear down **from the original checkout** (never from inside the
   worktree), which must be on `main`. After the rebase the branch is strictly
   ahead of `main`, so the merge is a guaranteed fast-forward:

   ```sh
   nix develop -c git -C "<original-checkout>" merge --ff-only quick/<slug>
   nix develop -c git -C "<original-checkout>" worktree remove "<worktree>"
   nix develop -c git -C "<original-checkout>" branch -d quick/<slug>
   ```

   `git worktree remove` refuses if the worktree still has uncommitted changes —
   a safety check, not something to force past.
5. Report the landed commit hash and a one-line summary.
