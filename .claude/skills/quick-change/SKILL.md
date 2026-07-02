---
name: quick-change
description: >-
  Lightweight end-to-end process for small, low-risk code changes: branch off
  main, implement, review the diff, then commit and fast-forward merge back
  into main. The quick sibling of rfc-pipeline — no RFC document, no worktree,
  no fan-out review. Use when a user asks for a "quick change", "small fix",
  "quick patch", or invokes /quick-change.
---

# Quick Change

Runs one small code change through a complete branch → implement → review → land
cycle in the current checkout. Everything happens inline in this session; no
background agents, no worktree, no RFC.

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

## Phase 1 — Branch

All git/toolchain commands run via `nix develop -c` per CLAUDE.md.

1. Require a **clean working tree** on `main` (`git status --porcelain`). If
   there are unrelated uncommitted changes, stop and ask the user how to proceed
   — never stash or commit someone else's work-in-progress.
2. Create the branch: `git switch -c quick/<slug> main`, where `<slug>` is a
   short kebab-case summary of the change (e.g. `quick/fix-login-typo`).

## Phase 2 — Implement

1. Make the change, honoring the baseline skills (`coding`, `kotlin-coding`,
   `general-design`, `shell-scripts` — whichever apply to the touched files).
2. If the change alters behavior described in a touched directory's **SPEC.md**,
   update that SPEC.md in the same change (SPEC.md is LLM-managed, no human
   gate). Do **not** touch INVARIANTS.md — that is human-gated; if the change
   affects an invariant, tell the user rather than editing it.
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
2. Commit through the full gate — no `--no-verify`, the checkpoint carve-out
   does not apply here:

   ```sh
   nix develop -c git add <explicit files>
   nix develop -c git commit ...
   ```

3. Merge and clean up:

   ```sh
   nix develop -c git switch main
   nix develop -c git merge --ff-only quick/<slug>
   nix develop -c git branch -d quick/<slug>
   ```

   If `main` advanced and the fast-forward fails, rebase the branch onto `main`,
   re-run the affected tests, and retry the ff-merge. If the rebase conflicts,
   stop and report — do not force anything.

4. Report the landed commit hash and a one-line summary.
